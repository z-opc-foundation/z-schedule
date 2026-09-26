package com.zifang.z.schedule.web.domain.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import com.zifang.z.schedule.web.service.JobLogService;
import com.zifang.z.schedule.web.service.impl.JobLogServiceImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 聚合 SQL 的数据库层验证.
 * <p>
 * {@link com.zifang.z.schedule.web.service.impl.JobLogServiceImplQueryTest} 用 Proxy 假 mapper
 * 只能钉"SQL 长什么样"; 本类钉"这条 SQL 在真实数据库上跑不跑得动、数得对不对"——
 * {@code DATE(trigger_time)} 是分日趋势的唯一依赖, 它在 H2 上属于 MySQL 兼容函数,
 * 一旦驱动行为变化(或换数据库), 只有这一层能立刻变红.
 * <p>
 * 连接串与 {@code z-schedule-admin/src/main/resources/application-dev.yml} 保持一致
 * (只有 MODE=MySQL, 没有 DATABASE_TO_LOWER), 因此列标签会折成大写 TOTAL/SUCCESS/STAT_DAY:
 * 这正是 {@code number()}/{@code dayKey()} 大小写不敏感查找的真实用武之地.
 */
public class JobLogMapperH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_agg;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private SqlSessionFactory factory;
    private SqlSession session;
    private JobLogMapper mapper;
    private JobLogServiceImpl service;

    @Before
    public void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            s.execute("CREATE TABLE z_schedule_job_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "job_group INT, job_id INT,"
                    + "executor_address VARCHAR(255), executor_handler VARCHAR(255),"
                    + "executor_param VARCHAR(255), executor_sharding_param VARCHAR(255),"
                    + "executor_fail_retry_count INT,"
                    + "trigger_time TIMESTAMP, trigger_code INT, trigger_msg VARCHAR(2048),"
                    + "handle_time TIMESTAMP, handle_code INT NOT NULL DEFAULT 0, handle_msg VARCHAR(2048),"
                    + "alarm_status INT)");
        }

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("h2", new JdbcTransactionFactory(), ds));
        // defaults() 而不是 new GlobalConfig(): 后者 dbConfig 为 null, 解析 @Select 时直接 NPE
        GlobalConfigUtils.setGlobalConfig(cfg, GlobalConfigUtils.defaults());
        cfg.addMapper(JobLogMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(cfg);

        session = factory.openSession(true);
        mapper = session.getMapper(JobLogMapper.class);
        service = new JobLogServiceImpl();
        Field f = JobLogServiceImpl.class.getDeclaredField("jobLogMapper");
        f.setAccessible(true);
        f.set(service, mapper);
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void insert(String when, int handleCode) {
        JobLogDO d = new JobLogDO();
        d.setJobId(7);
        d.setTriggerTime(ts(when));
        d.setHandleCode(handleCode);
        mapper.insert(d);
    }

    private static Timestamp ts(String when) {
        return Timestamp.valueOf(when);
    }

    private static Date at(String when) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(when);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    public void 区间统计只算窗口内的行且上界不含() {
        insert("2026-09-23 23:59:59", 200); // 早于下界
        insert("2026-09-24 00:00:00", 200); // 正好等于下界, 计入
        insert("2026-09-24 12:00:00", 502); // 窗口内, 非成功
        insert("2026-09-25 00:00:00", 200); // 正好等于上界, 不计入

        JobLogService.Stats stats = service.statsBetween(at("2026-09-24 00:00:00"), at("2026-09-25 00:00:00"));

        assertEquals("区间应为 2 行", 2L, stats.getTotal());
        assertEquals("只有 handle_code=200 算成功", 1L, stats.getSuccess());
        assertEquals(50, stats.successRate());
    }

    @Test
    public void 按日聚合按自然日分桶() {
        insert("2026-09-24 23:59:59", 200);
        insert("2026-09-25 00:00:01", 200);
        insert("2026-09-25 12:00:00", 502);
        insert("2026-09-26 01:02:03", 0);
        insert("2026-09-26 09:02:03", 500);

        Map<String, JobLogService.Stats> byDay = service.dailyStatsSince(at("2026-09-24 00:00:00"));

        assertEquals("三个自然日三个桶, 空日期不占位", 3, byDay.size());
        assertEquals(1L, byDay.get("2026-09-24").getTotal());
        assertEquals(1L, byDay.get("2026-09-24").getSuccess());
        assertEquals(2L, byDay.get("2026-09-25").getTotal());
        assertEquals(1L, byDay.get("2026-09-25").getSuccess());
        assertEquals("handle_code=0/500 都不算成功", 0L, byDay.get("2026-09-26").getSuccess());
    }

    @Test
    public void 驱动折成大写列标签时统计不得归零() {
        insert("2026-09-25 10:00:00", 200);
        insert("2026-09-25 11:00:00", 502);

        Map<String, Object> raw = mapper.statsBetween(at("2026-09-25 00:00:00"), at("2026-09-26 00:00:00"), 200);
        assertNotNull("COUNT 聚合至少要有一行", raw);
        // 猎物: 驱动给的标签确实是大写, 否则本用例只是空跑
        assertTrue("本用例的前提是驱动折大写, 实际标签=" + raw.keySet(), raw.containsKey("TOTAL"));
        assertFalse("若驱动已返回小写 total, 大小写兼容这层就是空转, 要改测试而不是留着",
                raw.containsKey("total"));

        JobLogService.Stats stats = service.statsBetween(at("2026-09-25 00:00:00"), at("2026-09-26 00:00:00"));
        assertEquals("列名大小写不同不能影响读数", 2L, stats.getTotal());
        assertEquals(1L, stats.getSuccess());
    }

    @Test
    public void 日期键接受驱动返回的java_sql_Date() {
        insert("2026-09-25 10:00:00", 200);

        java.util.List<Map<String, Object>> rows = mapper.dailyStats(at("2026-09-25 00:00:00"), 200);
        assertEquals(1, rows.size());
        Object day = rows.get(0).get("STAT_DAY");
        assertTrue("H2 的 DATE() 返回 java.sql.Date, 实际=" + (day == null ? "null" : day.getClass().getName()),
                day instanceof java.sql.Date);

        Map<String, JobLogService.Stats> byDay = service.dailyStatsSince(at("2026-09-25 00:00:00"));
        assertEquals("必须能按 yyyy-MM-dd 取到", 1, byDay.size());
        assertNotNull(byDay.get("2026-09-25"));
    }

    @Test
    public void 空表统计为零而不是空指针() {
        JobLogService.Stats stats = service.statsBetween(at("2026-09-24 00:00:00"), at("2026-09-25 00:00:00"));

        assertEquals(0L, stats.getTotal());
        assertEquals("SUM 在空集上是 NULL, 靠 COALESCE 兜住", 0L, stats.getSuccess());
        assertEquals(0, stats.successRate());
        assertTrue(service.dailyStatsSince(at("2026-09-24 00:00:00")).isEmpty());
    }
}
