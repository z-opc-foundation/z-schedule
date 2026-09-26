package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import com.zifang.z.schedule.web.domain.mapper.JobLogMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 日志清理的数据库层验证：删得对不对，以及一批有没有上界。
 * <p>
 * "一批多少行"是这条路径唯一的性能主张——{@code DELETE WHERE trigger_time < ?} 的范围
 * 取决于保留期多长、调度中心停了多久，没有任何上界，而单条大 DELETE 的锁持有时间、
 * undo 和 binlog 事件大小都跟着一起涨。所以这里用一层记录型 mapper 代理，
 * 把"每次删除实际发了多少行"钉成可断言的量。
 * <p>
 * 删除语句本身跑在真实 H2 上：MP 的 {@code deleteByIds} 生成什么 SQL、
 * 能不能按主键批量删掉，只有这一层能说。
 */
public class JobLogServiceImplClearH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_clear;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private SqlSession session;
    private JobLogMapper real;
    /** 记录型代理收到的删除调用，每个元素是该批的 id 个数. */
    private List<Integer> deleteBatches;
    private JobLogServiceImpl service;

    @Before
    public void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            s.execute("CREATE TABLE z_schedule_job_log ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "job_group INT NOT NULL DEFAULT 0, job_id INT NOT NULL,"
                    + "executor_address VARCHAR(255), executor_handler VARCHAR(255),"
                    + "executor_param VARCHAR(512), executor_sharding_param VARCHAR(64),"
                    + "executor_fail_retry_count INT NOT NULL DEFAULT 0,"
                    + "trigger_time TIMESTAMP, trigger_code INT NOT NULL DEFAULT 0,"
                    + "trigger_msg VARCHAR(512), handle_time TIMESTAMP,"
                    + "handle_code INT NOT NULL DEFAULT 0, handle_msg VARCHAR(2048),"
                    + "alarm_status TINYINT NOT NULL DEFAULT 0)");
            s.execute("CREATE INDEX idx_job_id ON z_schedule_job_log (job_id)");
            s.execute("CREATE INDEX idx_trigger_time ON z_schedule_job_log (trigger_time)");
        }

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("h2", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(cfg, GlobalConfigUtils.defaults());
        cfg.addMapper(JobLogMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(true);
        real = session.getMapper(JobLogMapper.class);

        deleteBatches = new ArrayList<Integer>();
        final List<Integer> batches = deleteBatches;
        JobLogMapper recording = (JobLogMapper) Proxy.newProxyInstance(
                JobLogMapper.class.getClassLoader(),
                new Class<?>[]{JobLogMapper.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                        if (isDelete(m) && args != null && args.length == 1 && args[0] instanceof Collection) {
                            batches.add(((Collection<?>) args[0]).size());
                        }
                        try {
                            return m.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });

        service = new JobLogServiceImpl();
        Field f = JobLogServiceImpl.class.getDeclaredField("jobLogMapper");
        f.setAccessible(true);
        f.set(service, recording);
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private static boolean isDelete(Method m) {
        return m.getName().startsWith("delete");
    }

    private long insertAt(Date triggerTime) {
        JobLogDO d = new JobLogDO();
        d.setJobId(7);
        d.setTriggerTime(triggerTime);
        d.setTriggerCode(200);
        d.setHandleCode(200);
        real.insert(d);
        return d.getId();
    }

    private static Date daysAgo(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }

    private List<Long> remainingIds() {
        List<JobLogDO> rows = real.selectList((Wrapper<JobLogDO>) null);
        List<Long> ids = new ArrayList<Long>(rows.size());
        for (JobLogDO r : rows) {
            ids.add(r.getId());
        }
        return ids;
    }

    // ---- 正确性 ----

    @Test
    public void 只删保留期之前的行() {
        long old1 = insertAt(daysAgo(10));
        long old2 = insertAt(daysAgo(8));
        long keep1 = insertAt(daysAgo(6));
        long keep2 = insertAt(daysAgo(1));

        assertEquals(2, service.clearLogByDays(7));
        assertEquals(Arrays.asList(keep1, keep2), remainingIds());
        assertTrue(old1 != keep1);
    }

    @Test
    public void 正好等于上界的行不删() {
        Date cutoff = daysAgo(3);
        long boundary = insertAt(cutoff);
        long older = insertAt(new Date(cutoff.getTime() - 1));
        long newer = insertAt(new Date(cutoff.getTime() + 60_000L));

        assertEquals("trigger_time 恰好等于上界属于仍在保留期内", 1, service.deleteBefore(cutoff));
        assertEquals(Arrays.asList(boundary, newer), remainingIds());
        assertTrue(older > 0);
    }

    @Test
    public void 保留天数低于下限时按七天计() {
        long tooOld = insertAt(daysAgo(9));
        long inside = insertAt(daysAgo(5));

        assertEquals(1, service.clearLogByDays(1));
        assertEquals("days=1 也必须按 7 天下限执行", Arrays.asList(inside), remainingIds());
        assertTrue(tooOld > 0);
    }

    @Test
    public void 没有过期行时不发删除语句() {
        insertAt(daysAgo(1));
        insertAt(daysAgo(2));

        assertEquals(0, service.clearLogByDays(7));
        assertEquals("无事可做就不该发 DELETE", java.util.Collections.emptyList(), deleteBatches);
    }

    // ---- 分批 ----

    @Test
    public void 单次删除的行数有上界() {
        Date cutoff = new Date();
        for (int i = 0; i < 2500; i++) {
            insertAt(daysAgo(30));
        }
        deleteBatches.clear();

        assertEquals(2500, service.deleteBefore(cutoff));
        assertEquals("2500 行至少要 3 批", 3, deleteBatches.size());
        int max = 0;
        int sum = 0;
        for (int n : deleteBatches) {
            max = Math.max(max, n);
            sum += n;
        }
        assertEquals("每批不得超过一批上限", JobLogServiceImpl.CLEAR_BATCH_SIZE, max);
        assertEquals(2500, sum);
        assertEquals(0L, remainingIds().size());
    }

    @Test
    public void 按主键批量删除真的删到了行() {
        Date cutoff = new Date();
        long a = insertAt(daysAgo(40));
        long b = insertAt(daysAgo(41));
        long keep = insertAt(new Date(cutoff.getTime() + 3_600_000L));

        assertEquals(2, service.deleteBefore(cutoff));
        List<Long> left = remainingIds();
        assertEquals(Arrays.asList(keep), left);
        assertTrue(a != keep && b != keep);
    }

    /**
     * 上一批"删了 0 行"时必须收手：只按 select 的结果判断是否继续，就会在一批永远删不掉的行上
     * 反复重查。用一个"总是返回一批 id、总是删掉 0 行"的桩 mapper 把这件事变成可数的量：
     * 正确实现只查一次，多查一次就是没有那个收手条件。
     */
    @Test
    public void 删不动时不得反复重查() throws Exception {
        final int[] selects = {0};
        final int[] deletes = {0};
        JobLogMapper stubborn = (JobLogMapper) Proxy.newProxyInstance(
                JobLogMapper.class.getClassLoader(),
                new Class<?>[]{JobLogMapper.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                        if (m.getName().equals("selectList")) {
                            selects[0]++;
                            // 最多演 5 轮：没有收手条件的实现会走到这里把查询次数堆起来，
                            // 但一定结束，不会把量具挂死
                            if (selects[0] > 5) {
                                return new ArrayList<JobLogDO>();
                            }
                            List<JobLogDO> rows = new ArrayList<JobLogDO>();
                            for (int i = 0; i < JobLogServiceImpl.CLEAR_BATCH_SIZE; i++) {
                                JobLogDO d = new JobLogDO();
                                d.setId((long) i);
                                rows.add(d);
                            }
                            return rows;
                        }
                        if (isDelete(m)) {
                            deletes[0]++;
                            return Integer.valueOf(0);
                        }
                        return null;
                    }
                });
        Field f = JobLogServiceImpl.class.getDeclaredField("jobLogMapper");
        f.setAccessible(true);
        f.set(service, stubborn);

        assertEquals("一批删 0 行就要返回", 0, service.deleteBefore(new Date()));
        assertEquals("发了一次删除就够了, 不该再查第二轮", 1, deletes[0]);
        assertEquals(1, selects[0]);
    }
}
