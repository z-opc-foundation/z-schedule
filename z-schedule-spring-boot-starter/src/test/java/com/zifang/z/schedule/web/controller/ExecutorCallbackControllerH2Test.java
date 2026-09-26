package com.zifang.z.schedule.web.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.KillParam;
import com.zifang.z.schedule.core.param.TriggerParam;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import com.zifang.z.schedule.web.domain.mapper.JobLogMapper;
import com.zifang.z.schedule.web.service.ExecutorRegistryService;
import com.zifang.z.schedule.web.service.JobLogService;
import com.zifang.z.schedule.web.service.JobTriggerService;
import com.zifang.z.schedule.web.service.impl.JobLogServiceImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 执行器回调链路的端到端验证：{@code /executor/run} 建日志 → {@code /executor/callback} 回写结果。
 * <p>
 * 这一层必须跑在真实数据库上：回调的语义全在"哪一列被改写了"，
 * 而 MyBatis-Plus 的 {@code updateById} 是<b>按 null 判定</b>拼 SET 子句的——
 * {@link JobLog} 的 jobId/triggerCode/alarmStatus 都是原始 int，反序列化后是 0 而不是 null，
 * 于是"只回写执行结果"的一次回调会把日志的归属和调度结果一并清零。用假 service 记参数看不出来。
 * <p>
 * 读回一律走裸 JDBC（不经过被测的 mapper/DoMapper），否则 mapper 自己的映射 bug 会和被测代码互相圆场。
 */
public class ExecutorCallbackControllerH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_callback;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private JdbcDataSource ds;
    private SqlSession session;
    private JobLogMapper mapper;
    private JobLogService jobLogService;

    private ExecutorCallbackController controller;
    private RecordingRegistry registry;
    private RecordingTrigger trigger;

    @Before
    public void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            // 列的可空性照 z_schedule_job_log 的建表脚本，不放宽
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
        mapper = session.getMapper(JobLogMapper.class);

        JobLogServiceImpl logService = new JobLogServiceImpl();
        inject(logService, "jobLogMapper", mapper);
        jobLogService = logService;

        registry = new RecordingRegistry();
        trigger = new RecordingTrigger();
        controller = new ExecutorCallbackController();
        inject(controller, "registryService", registry);
        inject(controller, "jobLogService", jobLogService);
        inject(controller, "jobTriggerService", trigger);
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException gone) {
                continue;
            }
        }
        throw new NoSuchFieldException(target.getClass() + "." + fieldName);
    }

    /** 直接落一行"调度侧已经写好"的日志，绕开被测代码. */
    private long seedLog(int jobGroup, int jobId, Date triggerTime, int triggerCode, String triggerMsg,
                         Date handleTime, int handleCode, String handleMsg) {
        JobLogDO d = new JobLogDO();
        d.setJobGroup(jobGroup);
        d.setJobId(jobId);
        d.setExecutorHandler("demoHandler");
        d.setTriggerTime(triggerTime);
        d.setTriggerCode(triggerCode);
        d.setTriggerMsg(triggerMsg);
        d.setHandleTime(handleTime);
        d.setHandleCode(handleCode);
        d.setHandleMsg(handleMsg);
        d.setAlarmStatus(0);
        mapper.insert(d);
        return d.getId();
    }

    /** 裸 JDBC 读回，键为小写列名. */
    private Map<String, Object> readRow(long id) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM z_schedule_job_log WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ResultSetMetaData md = rs.getMetaData();
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    row.put(md.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
                }
                return row;
            }
        }
    }

    private long rowCount() throws Exception {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM z_schedule_job_log")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Date at(String when) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(when);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static TriggerParam triggerParam(int jobId, long logId) {
        TriggerParam p = new TriggerParam();
        p.setJobId(jobId);
        p.setExecutorHandler("demoHandler");
        p.setExecutorParams("p-1");
        p.setExecutorFailRetryCount(2);
        p.setLogId(logId);
        return p;
    }

    // ---- /executor/run ----

    @Test
    public void 派发要建日志并带调度时间() {
        ReturnT<?> r = controller.run(triggerParam(7, 0L));

        assertEquals(200, r.getCode());
        assertEquals("一次派发一行日志", 1L, rowCountQuietly());

        Map<String, Object> row = readQuietly(lastLogId());
        assertNotNull(row);
        assertEquals(7L, asLong(row.get("job_id")));
        assertEquals(200L, asLong(row.get("trigger_code")));
        assertEquals(2L, asLong(row.get("executor_fail_retry_count")));
        assertNotNull("trigger_time 为空 ⇒ 这行日志落在任何时间窗口之外，统计里永远看不见它",
                row.get("trigger_time"));
    }

    @Test
    public void 派发的日志必须能被统计数到() {
        controller.run(triggerParam(7, 0L));

        JobLogService.Stats today = jobLogService.statsBetween(midnight(0), midnight(1));
        assertEquals("刚派发的日志应落在今天这一格, 实际=" + today, 1L, today.getTotal());

        Map<String, JobLogService.Stats> byDay = jobLogService.dailyStatsSince(midnight(0));
        assertEquals("按日聚合同样不能漏", 1, byDay.size());
    }

    @Test
    public void 派发要把logId回给执行器() {
        // 用 ReturnT<?> 而不是具体泛型: 本用例断言的就是 content 里到底有没有东西,
        // 写死 ReturnT<String> 会让"补上返回类型"这一步变成编译错误而不是一个红的用例
        ReturnT<?> r = controller.run(triggerParam(7, 0L));

        assertNotNull("执行器拿不到 logId 就无从回调, 这个任务将永远停在\"已下发\"", r.getContent());
        Object logId = ((Map<?, ?>) r.getContent()).get("logId");
        assertTrue("content.logId 必须是派发出来的那一行: " + logId, logId instanceof Number);
        assertEquals(asLong(logId), lastLogId());
    }

    @Test
    public void jobId非法的派发不落库() {
        assertEquals(400, controller.run(triggerParam(0, 0L)).getCode());
        assertEquals(400, controller.run(triggerParam(-3, 0L)).getCode());
        assertEquals(0L, rowCountQuietly());
    }

    // ---- /executor/callback ----

    @Test
    public void 回调只回写执行结果不清零日志归属() throws Exception {
        long id = seedLog(3, 7, at("2026-09-25 10:00:00"), 200, "已下发到执行器", null, 0, null);

        JobLog cb = new JobLog();
        cb.setId(id);
        cb.setHandleCode(ReturnT.SUCCESS_CODE);
        cb.setHandleMsg("执行成功, 耗时 12ms");
        cb.setHandleTime(at("2026-09-25 10:00:12"));
        assertEquals(200, controller.callback(cb).getCode());

        Map<String, Object> row = readRow(id);
        assertEquals(200L, asLong(row.get("handle_code")));
        assertEquals("执行成功, 耗时 12ms", row.get("handle_msg"));
        assertEquals("回调体里的 0 不能把日志改写成\"不属于任何任务\"", 7L, asLong(row.get("job_id")));
        assertEquals(3L, asLong(row.get("job_group")));
        assertEquals("调度结果归调度侧, 回调不许改写", 200L, asLong(row.get("trigger_code")));
        assertEquals("已下发到执行器", row.get("trigger_msg"));
        assertEquals(0L, asLong(row.get("alarm_status")));
        assertEquals("调度时间不能被回调动", Timestamp.valueOf("2026-09-25 10:00:00"), row.get("trigger_time"));
    }

    @Test
    public void 回调不存在的日志必须报失败() throws Exception {
        JobLog cb = new JobLog();
        cb.setId(987654L);
        cb.setHandleCode(ReturnT.SUCCESS_CODE);
        cb.setHandleMsg("执行成功");
        cb.setHandleTime(new Date());

        ReturnT<String> r = controller.callback(cb);
        assertNot("对不存在的主键回写影响 0 行, 不能报成功——否则执行器认为已送达、日志永远停在\"已下发\"", r.getCode());
        assertEquals("也不能顺手插入一行", 0L, rowCount());
    }

    @Test
    public void 回调缺id时拒绝() {
        JobLog cb = new JobLog();
        cb.setHandleCode(ReturnT.SUCCESS_CODE);
        assertNot("", controller.callback(cb).getCode());
        cb.setId(-1L);
        assertNot("", controller.callback(cb).getCode());
    }

    @Test
    public void 同一结果重复回调是幂等的() throws Exception {
        long id = seedLog(3, 7, at("2026-09-25 10:00:00"), 200, "已下发到执行器", null, 0, null);

        for (int i = 0; i < 3; i++) {
            JobLog cb = new JobLog();
            cb.setId(id);
            cb.setHandleCode(500);
            cb.setHandleMsg("执行失败: boom");
            cb.setHandleTime(at("2026-09-25 10:00:20"));
            assertEquals("重发不能被当成错误: 第" + i + "次", 200, controller.callback(cb).getCode());
        }

        Map<String, Object> row = readRow(id);
        assertEquals(500L, asLong(row.get("handle_code")));
        assertEquals("执行失败: boom", row.get("handle_msg"));
        assertEquals(1L, rowCountQuietly());
    }

    @Test
    public void 迟到的回调不得覆盖更新的结果() throws Exception {
        long id = seedLog(3, 7, at("2026-09-25 10:00:00"), 200, "已下发到执行器",
                at("2026-09-25 10:00:30"), 502, "任务被终止");

        JobLog late = new JobLog();
        late.setId(id);
        late.setHandleCode(ReturnT.SUCCESS_CODE);
        late.setHandleMsg("执行成功");
        late.setHandleTime(at("2026-09-25 10:00:10")); // 早于已记录的时间
        controller.callback(late);

        Map<String, Object> row = readRow(id);
        assertEquals("乱序到达的旧回调不能改写终态", 502L, asLong(row.get("handle_code")));
        assertEquals("任务被终止", row.get("handle_msg"));

        JobLog fresh = new JobLog();
        fresh.setId(id);
        fresh.setHandleCode(500);
        fresh.setHandleMsg("重试后仍失败");
        fresh.setHandleTime(at("2026-09-25 10:01:00"));
        controller.callback(fresh);

        Map<String, Object> after = readRow(id);
        assertEquals("比已记录时间新的回调仍要正常生效", 500L, asLong(after.get("handle_code")));
        assertEquals("重试后仍失败", after.get("handle_msg"));
    }

    @Test
    public void 回调未带执行时间时补上当前时间() throws Exception {
        long id = seedLog(3, 7, at("2026-09-25 10:00:00"), 200, "已下发到执行器", null, 0, null);

        JobLog cb = new JobLog();
        cb.setId(id);
        cb.setHandleCode(ReturnT.SUCCESS_CODE);
        cb.setHandleMsg("执行成功");
        controller.callback(cb);

        assertNotNull("handle_time 为空 ⇒ 列表页的\"执行时间\"永远是空列", readRow(id).get("handle_time"));
    }

    // ---- /executor/kill ----

    @Test
    public void 终止只作用于同一任务下的日志() throws Exception {
        long mine = seedLog(3, 7, new Date(), 200, "已下发", null, 0, null);
        long others = seedLog(3, 8, new Date(), 200, "已下发", null, 0, null);

        ReturnT<String> ok = controller.kill(killParam(7, mine));
        assertEquals(200, ok.getCode());
        assertEquals("只有这一条被登记为终止", java.util.Arrays.asList(mine), trigger.killed);

        trigger.killed.clear();
        assertNot("", controller.kill(killParam(7, others)).getCode());
        assertTrue("按 logId 定位却无视 jobId ⇒ 任何持有 token 的执行器能终止别人的任务",
                trigger.killed.isEmpty());
    }

    @Test
    public void 终止缺logId或日志不存在时报失败() {
        trigger.killed.clear();
        // logId 缺失是请求写错(400)，与"id 对不上任何日志"要区分开，否则摘掉入参校验也能蒙过本用例
        assertEquals(400, controller.kill(killParam(7, 0L)).getCode());
        assertEquals(400, controller.kill(killParam(7, -1L)).getCode());
        assertNot("", controller.kill(killParam(7, 987654L)).getCode());
        assertEquals(400, controller.kill(killParam(0, 1L)).getCode());
        assertTrue("没定位到日志就不该调 killJob", trigger.killed.isEmpty());
    }

    // ---- /executor/beat 与 /executor/activeCount ----

    @Test
    public void 心跳优先取查询参数缺失时取body() {
        Map<String, Object> body = new java.util.HashMap<String, Object>();
        body.put("appName", "app-from-body");
        body.put("address", "http://127.0.0.1:9999");

        assertEquals(200, controller.beat(null, null, body).getCode());
        assertEquals("app-from-body", registry.lastAppName);
        assertEquals("http://127.0.0.1:9999", registry.lastAddress);

        assertEquals(200, controller.beat("app-from-param", "http://127.0.0.1:8888", body).getCode());
        assertEquals("查询参数优先", "app-from-param", registry.lastAppName);
        assertEquals("http://127.0.0.1:8888", registry.lastAddress);
    }

    @Test
    public void 心跳两项都不齐时不调用注册服务() {
        assertNot("", controller.beat(null, null, null).getCode());
        assertNot("", controller.beat("app", null, null).getCode());
        assertEquals(0, registry.beatCalls);
    }

    @Test
    public void 心跳里非字符串的字段要报参数错误而不是抛异常() {
        Map<String, Object> body = new java.util.HashMap<String, Object>();
        body.put("appName", 123); // JSON 里写成数字
        body.put("address", "http://127.0.0.1:9999");
        try {
            assertNot("", controller.beat(null, null, body).getCode());
        } catch (ClassCastException e) {
            fail("非字符串字段被强转 ⇒ HTTP 500, 而不是可读的参数错误: " + e);
        }
    }

    @Test
    public void 在线分组数来自注册服务() {
        registry.onlineGroups = 4;
        ReturnT<Integer> r = controller.activeCount();
        assertEquals(200, r.getCode());
        assertEquals(Integer.valueOf(4), r.getContent());
    }

    // ---- helpers that swallow checked exceptions for one-liner assertions ----

    private Date midnight(int dayOffset) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, dayOffset);
        return cal.getTime();
    }

    private long lastLogId() {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(id) FROM z_schedule_job_log")) {
            rs.next();
            long id = rs.getLong(1);
            assertFalse("还没有任何日志行", rs.wasNull());
            return id;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readQuietly(long id) {
        try {
            return readRow(id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long rowCountQuietly() {
        try {
            return rowCount();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertNot(String message, int code) {
        if (code == ReturnT.SUCCESS_CODE) {
            fail((message == null || message.isEmpty() ? "不能报成功" : message) + ", 实际 code=200");
        }
    }

    private static KillParam killParam(int jobId, long logId) {
        KillParam p = new KillParam();
        p.setJobId(jobId);
        p.setLogId(logId);
        return p;
    }

    private static class RecordingRegistry implements ExecutorRegistryService {
        final List<String> beats = new ArrayList<String>();
        String lastAppName;
        String lastAddress;
        int beatCalls;
        int onlineGroups;

        @Override
        public ReturnT<String> beat(String appName, String address) {
            beatCalls++;
            lastAppName = appName;
            lastAddress = address;
            beats.add(appName + "@" + address);
            return ReturnT.success("beat ok", null);
        }

        @Override
        public ReturnT<String> remove(String appName, String address) {
            return ReturnT.success();
        }

        @Override
        public int onlineGroupCount() {
            return onlineGroups;
        }

        @Override
        public List<Map<String, Object>> loadAll() {
            return new ArrayList<Map<String, Object>>();
        }
    }

    private static class RecordingTrigger implements JobTriggerService {
        final List<Long> killed = new ArrayList<Long>();

        @Override
        public void registerJob(com.zifang.z.schedule.core.model.JobInfo jobInfo) {
        }

        @Override
        public void cancelJob(int jobId) {
        }

        @Override
        public void triggerJob(com.zifang.z.schedule.core.model.JobInfo jobInfo) {
        }

        @Override
        public void completeJob(int jobId) {
        }

        @Override
        public void killJob(long logId) {
            killed.add(logId);
        }
    }
}
