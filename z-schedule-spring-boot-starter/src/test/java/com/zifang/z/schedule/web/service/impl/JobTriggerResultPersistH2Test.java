package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.core.enums.TriggerCodeEnum;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.web.cluster.JobScheduleEngine;
import com.zifang.z.schedule.web.cluster.LeaderElector;
import com.zifang.z.schedule.web.domain.mapper.JobLogMapper;
import com.zifang.z.schedule.web.service.AlarmService;
import com.zifang.z.schedule.web.service.JobLogService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.StaticApplicationContext;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 进程内执行链路的<b>落库</b>验证：handler 跑完之后，结论到底有没有写进 {@code z_schedule_job_log}。
 *
 * <p>这一层必须用真实的 {@link JobLogServiceImpl} + 真实 mapper 跑，不能用假 service：
 * 曾经 {@code FakeJobLogService.save()} 里顺手写了 {@code jobLog.setId(id)}，而生产的
 * {@link JobLogService#save(JobLog)} 只把自增 id return 出去、不回填 DTO，于是
 * {@code JobTriggerServiceImpl} 里紧接着的 {@code update(log)} 撞上 {@code id <= 0} 的守卫
 * 直接静默 return——250 真机 MySQL 上 144 行日志的 handle_code 全是 0、handle_msg 全是 NULL。
 * 假 service 把这条断链圆过去了，只有真库能照出来。
 *
 * <p>读回一律走裸 JDBC（不经过被测 mapper），否则映射层的 bug 会和被测代码互相圆场。
 */
public class JobTriggerResultPersistH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_trigger_result;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private JdbcDataSource ds;
    private SqlSession session;

    private JobTriggerServiceImpl service;
    private FakeAlarmService alarmService;
    private CountingJobLogService countingLogService;

    @Before
    public void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            s.execute("CREATE TABLE z_schedule_job_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "job_group INT NOT NULL DEFAULT 0,"
                    + "job_id INT NOT NULL,"
                    + "executor_address VARCHAR(255),"
                    + "executor_handler VARCHAR(255),"
                    + "executor_param VARCHAR(512),"
                    + "executor_sharding_param VARCHAR(64),"
                    + "executor_fail_retry_count INT NOT NULL DEFAULT 0,"
                    + "trigger_time TIMESTAMP,"
                    + "trigger_code INT NOT NULL DEFAULT 0,"
                    + "trigger_msg VARCHAR(512),"
                    + "handle_time TIMESTAMP,"
                    + "handle_code INT NOT NULL DEFAULT 0,"
                    + "handle_msg VARCHAR(2048),"
                    + "alarm_status TINYINT NOT NULL DEFAULT 0)");
        }

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("h2", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(cfg, GlobalConfigUtils.defaults());
        cfg.addMapper(JobLogMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(true);
        JobLogMapper mapper = session.getMapper(JobLogMapper.class);

        JobLogServiceImpl logServiceImpl = new CountingJobLogService();
        countingLogService = (CountingJobLogService) logServiceImpl;
        inject(logServiceImpl, "jobLogMapper", mapper);

        service = new JobTriggerServiceImpl();
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("okHandler", new OkHandler());
        context.getBeanFactory().registerSingleton("boomHandler", new BoomHandler());

        alarmService = new FakeAlarmService();
        inject(service, "applicationContext", context);
        inject(service, "jobLogService", logServiceImpl);
        inject(service, "alarmService", alarmService);
        inject(service, "leaderElector", new AlwaysLeader());
        inject(service, "scheduleProperties", new ScheduleProperties());
        service.setEngine(new NoopEngine());
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    // ---- 成功路径 ----

    @Test
    public void 成功执行后库里必须有结论而不是一行未完成的日志() throws Exception {
        OkHandler.calls.set(0);
        service.triggerJob(job("okHandler"));

        assertEquals(1, OkHandler.calls.get());

        Row row = onlyRow();
        assertEquals("成功执行的 handle_code 没落库：库里还是插入时的 0",
                200, row.handleCode);
        assertNotNull("handle_msg 必须落库，否则列表页执行结果列永久空白", row.handleMsg);
        assertTrue(row.handleMsg, row.handleMsg.contains("执行成功"));
        assertNotNull("handle_time 没落库，前端算不出耗时", row.handleTime);
        assertEquals("成功不该置告警状态", 0, row.alarmStatus);
    }

    // ---- 失败路径 ----

    @Test
    public void 失败执行的结论与告警状态必须落库() throws Exception {
        BoomHandler.calls.set(0);
        service.triggerJob(job("boomHandler"));

        assertEquals(1, BoomHandler.calls.get());

        Row row = onlyRow();
        assertEquals("抛异常的 job 库里必须记成 FAIL",
                TriggerCodeEnum.FAIL.getCode(), row.handleCode);
        assertNotNull(row.handleMsg);
        assertTrue(row.handleMsg, row.handleMsg.contains("boom"));
        assertNotNull(row.handleTime);
        assertEquals("失败要落 alarm_status，清理/告警查询都读这一列", 1, row.alarmStatus);
        assertEquals(1, alarmService.calls.get());
    }

    @Test
    public void 执行器缺失要落库成EXECUTOR_NOT_FOUND而不是隐形成功() throws Exception {
        // 250 真机上就是这个形状：演示应用里根本没有 executorHandler bean。
        // 这种 job 的日志若不带结论落库，看上去和"正在执行"完全一样。
        service.triggerJob(job("handlerThatDoesNotExist"));

        Row row = onlyRow();
        assertEquals(TriggerCodeEnum.EXECUTOR_NOT_FOUND.getCode(), row.handleCode);
        assertNotNull(row.handleMsg);
        assertTrue(row.handleMsg, row.handleMsg.contains("handlerThatDoesNotExist"));
        assertNotNull(row.handleTime);
    }

    @Test
    public void 未指定executorHandler的拒绝也必须落库() throws Exception {
        service.triggerJob(job("   "));

        Row row = onlyRow();
        assertEquals(TriggerCodeEnum.INVALID_PARAM.getCode(), row.handleCode);
        assertNotNull(row.handleMsg);
        assertTrue(row.handleMsg, row.handleMsg.contains("executorHandler"));
    }

    // ---- 写放大：一次失败到底往库里写几条 ----

    @Test
    public void 告警没有改写状态时不得再发第二条UPDATE() throws Exception {
        // 250 真机 MySQL 8 实测：每条失败日志固定产生 2 条 UPDATE，第二条写回的内容
        // 与第一条逐字节相同——DefaultAlarmService 在没配邮箱时直接 return，
        // 而 finishFailure 无条件又写了一遍。百任务规模下这占掉 job_log 写入量的 2/3。
        BoomHandler.calls.set(0);
        service.triggerJob(job("boomHandler"));

        assertEquals("结论该落的还是得落", TriggerCodeEnum.FAIL.getCode(), onlyRow().handleCode);
        assertEquals(1, alarmService.calls.get());
        assertEquals(1, countingLogService.saves.get());
        assertEquals("alarmStatus 没被 sendAlarm 改动 ⇒ 第二条 UPDATE 是纯写放大",
                1, countingLogService.updates.get());
    }

    @Test
    public void 告警改写了状态就必须把那一条补上() throws Exception {
        // 上一例的对照：若把两条 UPDATE 一起删掉，上一例照样绿，这一例必红。
        alarmService.mutatesTo = 2; // 扮演"邮件真的发出去了"的实现
        BoomHandler.calls.set(0);
        service.triggerJob(job("boomHandler"));

        assertEquals(2, countingLogService.updates.get());
        assertEquals("告警后的新状态必须落库，否则告警查询读到的还是失败时的 1",
                2, onlyRow().alarmStatus);
    }

    // ---- 重试路径：每轮一行，且每行都带自己的结论 ----

    @Test
    public void 重试的每一轮都必须各自落库带结论() throws Exception {
        BoomHandler.calls.set(0);
        JobInfo job = job("boomHandler");
        job.setExecutorFailRetryCount(2);
        service.triggerJob(job);

        assertEquals("首执 + 2 次重试", 3, BoomHandler.calls.get());

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT handle_code, trigger_msg FROM z_schedule_job_log ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            int n = 0;
            while (rs.next()) {
                n++;
                assertEquals("第 " + n + " 轮日志没有结论，handle_code 仍是插入时的 0",
                        TriggerCodeEnum.FAIL.getCode(), rs.getInt(1));
            }
            assertEquals(3, n);
        }
    }

    // ---- save() 的 DTO 契约 ----

    @Test
    public void save必须把自增id回填进DTO() throws Exception {
        JobLogService logService = (JobLogService) readField(service, "jobLogService");
        JobLog log = new JobLog();
        log.setJobId(7);
        log.setTriggerCode(200);

        long returned = logService.save(log);

        assertTrue(returned > 0);
        assertEquals("save() 必须让调用方手上的 DTO 也带上 id："
                + "否则紧接着的 update() 会被 id<=0 的守卫静默吞掉", returned, log.getId());
    }

    // ---- fixtures ----

    private static JobInfo job(String handler) {
        JobInfo info = new JobInfo();
        info.setId(1);
        info.setJobGroup(1);
        info.setTriggerType("CRON");
        info.setJobCron("0 0 0 ? * MON-SAT");
        info.setExecutorHandler(handler);
        info.setExecutorParam("p");
        info.setExecutorFailRetryCount(0);
        info.setExecutorTimeout(0);
        info.setTriggerStatus(1);
        return info;
    }

    private Row onlyRow() throws Exception {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement count = c.prepareStatement(
                    "SELECT COUNT(*) FROM z_schedule_job_log");
                 ResultSet rs = count.executeQuery()) {
                rs.next();
                // 多于一条时按第一条判定会让断言失真，先钉死基数
                assertEquals("期望库里恰好一行", 1, rs.getInt(1));
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT job_id, handle_code, handle_msg, handle_time, alarm_status "
                            + "FROM z_schedule_job_log ORDER BY id LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue("库里一条日志都没有：save 就没落库", rs.next());
                Row row = new Row();
                row.jobId = rs.getInt(1);
                row.handleCode = rs.getInt(2);
                row.handleMsg = rs.getString(3);
                row.handleTime = rs.getTimestamp(4);
                row.alarmStatus = rs.getInt(5);
                return row;
            }
        }
    }

    private static class Row {
        int jobId;
        int handleCode;
        String handleMsg;
        java.sql.Timestamp handleTime;
        int alarmStatus;
    }

    public static class OkHandler {
        static final AtomicInteger calls = new AtomicInteger();

        public void execute(String param) {
            calls.incrementAndGet();
        }
    }

    public static class BoomHandler {
        static final AtomicInteger calls = new AtomicInteger();

        public void execute(String param) {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }
    }

    private static class FakeAlarmService implements AlarmService {
        final AtomicInteger calls = new AtomicInteger();
        /**
         * 非 null 时扮演"真的把告警发出去了"的 AlarmService —— 它会改写 alarmStatus。
         * 用于阳性对照：只验"少发一条 UPDATE"而不验"该发时还在发"，
         * 那么"把两条 UPDATE 全删了"也能让前者通过。
         */
        Integer mutatesTo;

        @Override
        public void sendAlarm(JobInfo job, JobLog log) {
            calls.incrementAndGet();
            if (mutatesTo != null) {
                log.setAlarmStatus(mutatesTo);
            }
        }
    }

    /** 真 service + 真 mapper，只在计数上做手脚：数的是"这次执行往库里写了几条"。 */
    private static class CountingJobLogService extends JobLogServiceImpl {
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger updates = new AtomicInteger();

        @Override
        public long save(JobLog jobLog) {
            saves.incrementAndGet();
            return super.save(jobLog);
        }

        @Override
        public void update(JobLog jobLog) {
            updates.incrementAndGet();
            super.update(jobLog);
        }
    }

    private static class AlwaysLeader extends LeaderElector {
        @Override
        public boolean isLeader() {
            return true;
        }
    }

    /** 本组测试只验落库，不跑时间轮；FIX_DELAY 的重挂在这里不该发生。 */
    private static class NoopEngine extends JobScheduleEngine {
        @Override
        public boolean scheduleJob(JobInfo info) {
            return true;
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object readField(Object target, String field) throws Exception {
        Field f = findField(target.getClass(), field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> type, String field) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(field + " on " + type);
    }
}
