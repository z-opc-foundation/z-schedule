package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.domain.ShippedSqlScript;
import com.zifang.z.schedule.web.domain.entity.JobInfoDO;
import com.zifang.z.schedule.web.domain.mapper.JobInfoMapper;
import com.zifang.z.schedule.web.service.JobTriggerService;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 任务生命周期的数据库层验证：{@code add/update/start/stop/delete} 到底往表里写了什么，
 * 以及"报成功"是不是真的能被引擎兑现。
 * <p>
 * 表结构来自随包的 {@code _doc/004_sql/z-schedule.sql}（{@link ShippedSqlScript}），不是这里另抄的
 * 简化 DDL——抄出来的建表语句会跟着作者记忆漂，而"列对不上"在构建期完全不红。
 * <p>
 * 断言一律走独立的裸 JDBC 读（{@link #row(int)}）：被服务的 mapper 同时当写入方和取证方时，
 * 它自己怎么映射列都"自证正确"。引擎侧则用记录型 {@link RecordingTrigger} 钉住
 * "失败的路径不许把任务交给时间轮"。
 */
public class JobInfoServiceImplH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_jobinfo;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private static final String GOOD_CRON = "0 * * * * ?";
    private static final String BAD_CRON = "0 0 * * *";

    private JdbcDataSource ds;
    private SqlSession session;
    private JobInfoMapper mapper;
    private JobInfoServiceImpl service;
    private RecordingTrigger trigger;

    @Before
    public void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        try (Connection c = ds.getConnection()) {
            ShippedSqlScript.applyTo(c);
        }

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("h2", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(cfg, GlobalConfigUtils.defaults());
        cfg.addMapper(JobInfoMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(true);
        mapper = session.getMapper(JobInfoMapper.class);

        trigger = new RecordingTrigger();
        service = new JobInfoServiceImpl();
        inject("jobInfoMapper", mapper);
        inject("jobTriggerService", trigger);
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = JobInfoServiceImpl.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(service, value);
    }

    // ==================== 新增 ====================

    @Test
    public void 新增任务落库并补上默认触发类型与过期策略() throws Exception {
        JobInfo job = job("每天清理", GOOD_CRON);
        ReturnT<String> added = service.add(job);
        assertTrue(added.getMsg(), added.isSuccess());

        List<Map<String, Object>> rows = rows();
        assertEquals("应当只落 1 行", 1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("每天清理", row.get("job_desc"));
        assertEquals(GOOD_CRON, row.get("job_cron"));
        assertEquals("CRON", row.get("trigger_type"));
        assertEquals("DO_NOTHING", row.get("misfire_strategy"));
        assertEquals(0, number(row.get("trigger_status")));
        assertEquals(0, number(row.get("trigger_last_time")));
        assertEquals(0, number(row.get("trigger_next_time")));
    }

    @Test
    public void 新增拒绝空描述空cron与语法错误的cron() {
        JobInfo noDesc = job(null, GOOD_CRON);
        noDesc.setJobDesc("   ");
        assertFalse("空描述不该新增", service.add(noDesc).isSuccess());

        assertFalse("空 cron 不该新增", service.add(job("x", "  ")).isSuccess());
        assertFalse("非法 cron 不该新增", service.add(job("x", BAD_CRON)).isSuccess());
    }

    @Test
    public void 新增的id回填并能按主键读回() {
        JobInfo job = job("可寻址", GOOD_CRON);
        ReturnT<String> added = service.add(job);
        assertTrue(added.getMsg(), added.isSuccess());
        int id = Integer.parseInt(added.getContent());
        assertTrue("没回填自增主键", id > 0);
        assertEquals("可寻址", service.getById(id).getJobDesc());
        assertNullRow(service.getById(id + 9999));
    }

    // ==================== 更新 ====================

    @Test
    public void 更新不能把cron改成空串() throws Exception {
        int id = addJob("清空cron", GOOD_CRON);

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setJobCron("");
        ReturnT<String> updated = service.update(patch);

        assertFalse("空串 cron 必须被拒绝——落库后引擎会静默不排期，而 trigger_status 仍是 1",
                updated.isSuccess());
        assertEquals("列值被改动: " + row(id).get("job_cron"), GOOD_CRON, row(id).get("job_cron"));

        patch.setJobCron("   ");
        assertFalse("纯空白 cron 同样要拒绝", service.update(patch).isSuccess());
        assertEquals(GOOD_CRON, row(id).get("job_cron"));
    }

    @Test
    public void 更新非法cron必须被拒绝且不改列值() throws Exception {
        int id = addJob("非法cron", GOOD_CRON);

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setJobCron(BAD_CRON);
        assertFalse(service.update(patch).isSuccess());
        assertEquals(GOOD_CRON, row(id).get("job_cron"));
    }

    @Test
    public void 运行中的任务不能改cron() throws Exception {
        int id = addJob("运行中", GOOD_CRON);
        setStatus(id, 1);

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setJobCron("0 0 3 * * ?");
        ReturnT<String> updated = service.update(patch);
        assertFalse("运行中改 cron 必须拒绝", updated.isSuccess());
        assertTrue("消息要说清 why: " + updated.getMsg(), updated.getMsg().contains("停止"));
        assertEquals(GOOD_CRON, row(id).get("job_cron"));
    }

    @Test
    public void 不传触发类型的更新保留原有列值() throws Exception {
        int id = addFixRateJob("FIX_RATE", 60_000L);

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setJobDesc("只改描述");
        ReturnT<String> updated = service.update(patch);
        assertTrue(updated.getMsg(), updated.isSuccess());

        Map<String, Object> row = row(id);
        assertEquals("只改描述", row.get("job_desc"));
        assertEquals("FIX_RATE", row.get("trigger_type"));
        assertEquals(60_000L, number(row.get("fix_interval")));
    }

    @Test
    public void 不带间隔字段的更新保留原间隔() throws Exception {
        int id = addFixRateJob("FIX_RATE", 60_000L);

        // 补丁里根本没有 fix_interval 这个键：装箱后的 DTO 读出来是 null，闸门按"没给"处理。
        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setTriggerType("FIX_RATE");
        ReturnT<String> updated = service.update(patch);
        assertTrue(updated.getMsg(), updated.isSuccess());
        assertEquals(60_000L, number(row(id).get("fix_interval")));
    }

    /**
     * 调用方**给了**一个非法间隔，就不能再按"没给"糊过去。
     * <p>
     * 装箱前这办不到：primitive long 下"没带这个键"和"带了 0"都是 0，于是 {@code > 0} 的闸门顺手
     * 把非法值也一起吞掉了——页面上填 0 保存，接口报成功，列值却还是 60000，用户以为自己改了。
     */
    @Test
    public void 显式给0或负数的FIX间隔必须被拒绝() throws Exception {
        int id = addFixRateJob("FIX_RATE", 60_000L);

        JobInfo zero = new JobInfo();
        zero.setId(id);
        zero.setTriggerType("FIX_RATE");
        zero.setFixInterval(0L);
        ReturnT<String> rejected = service.update(zero);
        assertFalse("显式 0 的 FIX_RATE 间隔不该被当成\"没给\"", rejected.isSuccess());
        assertTrue(rejected.getMsg(), rejected.getMsg().contains("间隔"));
        assertEquals(60_000L, number(row(id).get("fix_interval")));

        zero.setFixInterval(-1L);
        assertFalse("负数间隔同样要拒绝", service.update(zero).isSuccess());
        assertEquals(60_000L, number(row(id).get("fix_interval")));
    }

    /** 反向上限：CRON 任务不读 fix_interval，显式给 0 必须真的落 0（装箱不是"永不写")。 */
    @Test
    public void CRON任务可以显式清掉遗留的间隔() throws Exception {
        int id = insertRaw("带着遗留间隔的 cron", GOOD_CRON, "CRON", 30_000L);
        assertEquals("夹具没写进遗留间隔", 30_000L, number(row(id).get("fix_interval")));

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setTriggerType("CRON");
        patch.setFixInterval(0L);
        ReturnT<String> updated = service.update(patch);
        assertTrue("清间隔是合法操作: " + updated.getMsg(), updated.isSuccess());
        assertEquals(0, number(row(id).get("fix_interval")));
    }

    // ==================== 更新：partial update 不许抹列 ====================

    /**
     * "补丁里没带的字段"不能被写成 0。
     * <p>
     * DTO 的 executorTimeout / executorFailRetryCount 是 primitive int：JSON 里没这个键时反序列化成 0，
     * 与"调用方真的要设成 0"无法区分，而 {@code >= 0} 的合并闸门对 0 永远放行——于是任何只改描述的
     * partial update 都会把超时和重试次数抹成 0（等于悄悄关掉超时保护与失败重试），接口还报成功。
     */
    @Test
    public void 补丁不带超时与重试次数时保留列值() throws Exception {
        int id = addJob("只改描述", GOOD_CRON);
        setNumbers(id, 120, 3);
        Map<String, Object> before = row(id);
        // 猎物检查：列值确实是服务读得到的非零值，否则"没被抹"这条断言是在守一个空集
        assertEquals("夹具没写进超时", 120, number(before.get("executor_timeout")));
        assertEquals("夹具没写进重试次数", 3, number(before.get("executor_fail_retry_count")));

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setJobDesc("改个描述");
        // 刻意不调 setExecutorTimeout / setExecutorFailRetryCount：装箱前它们在这就是 0
        ReturnT<String> updated = service.update(patch);

        assertTrue(updated.getMsg(), updated.isSuccess());
        Map<String, Object> after = row(id);
        assertEquals("补丁给的那一列要真的改到", "改个描述", after.get("job_desc"));
        assertEquals("partial update 把超时抹成了 0", 120, number(after.get("executor_timeout")));
        assertEquals("partial update 把重试次数抹成了 0", 3, number(after.get("executor_fail_retry_count")));
    }

    /** 反向：调用方明确给了 0（"不限制超时"）就必须落 0，别把闸门焊成"这列永远改不动"。 */
    @Test
    public void 显式给0的超时与重试次数仍然生效() throws Exception {
        int id = addJob("清掉超时", GOOD_CRON);
        setNumbers(id, 120, 3);

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setExecutorTimeout(0);
        patch.setExecutorFailRetryCount(0);
        ReturnT<String> updated = service.update(patch);

        assertTrue(updated.getMsg(), updated.isSuccess());
        Map<String, Object> row = row(id);
        assertEquals("显式的\"不限超时\"该落 0", 0, number(row.get("executor_timeout")));
        assertEquals("显式的\"不重试\"该落 0", 0, number(row.get("executor_fail_retry_count")));

        patch.setExecutorTimeout(45);
        patch.setExecutorFailRetryCount(2);
        ReturnT<String> raised = service.update(patch);
        assertTrue(raised.getMsg(), raised.isSuccess());
        Map<String, Object> after = row(id);
        assertEquals(45, number(after.get("executor_timeout")));
        assertEquals(2, number(after.get("executor_fail_retry_count")));
    }

    @Test
    public void 把cron任务改成FIX类型却不给间隔必须拒绝() throws Exception {
        int id = addJob("cron 改 FIX", GOOD_CRON);

        JobInfo toFix = new JobInfo();
        toFix.setId(id);
        toFix.setTriggerType("FIX_RATE");
        ReturnT<String> updated = service.update(toFix);

        assertFalse("合并成 FIX_RATE/间隔0 的任务永远不会触发, 不该报成功: " + updated.getMsg(),
                updated.isSuccess());
        Map<String, Object> row = row(id);
        assertEquals("拒绝更新不该写坏列值: " + row.get("trigger_type"), "CRON", row.get("trigger_type"));
        assertEquals(0, number(row.get("fix_interval")));
    }

    @Test
    public void 新增FIX类型任务必须带正数间隔() throws Exception {
        JobInfo fix = job("没有间隔的FIX_RATE", GOOD_CRON);
        fix.setTriggerType("FIX_RATE");
        ReturnT<String> added = service.add(fix);
        assertFalse("间隔 0 的 FIX_RATE 一出生就不会触发", added.isSuccess());
        assertTrue(added.getMsg(), added.getMsg().contains("间隔"));

        fix.setFixInterval(15_000L);
        ReturnT<String> withInterval = service.add(fix);
        assertTrue(withInterval.getMsg(), withInterval.isSuccess());
    }

    /**
     * 250 真机实测：提交 {@code triggerType=FIX_DELAY, jobCron=""} 被打回 "Cron表达式不能为空"，
     * 于是引擎侧的 FIX 能力只能靠塞一个假 cron 才建得出来。cron 该只在引擎真会读它的类型上必填。
     */
    @Test
    public void 新增FIX_RATE任务不该要求cron() throws Exception {
        JobInfo fix = job("纯 FIX_RATE 无 cron", null);
        fix.setTriggerType("FIX_RATE");
        fix.setFixInterval(5_000L);
        ReturnT<String> added = service.add(fix);
        assertTrue("FIX_RATE 不读 cron, 却被挡回: " + added.getMsg(), added.isSuccess());
        int id = Integer.parseInt(added.getContent());
        assertEquals("FIX_RATE", row(id).get("trigger_type"));
        Object storedCron = row(id).get("job_cron");
        assertTrue("cron 该保持空, 实际 " + storedCron,
                storedCron == null || String.valueOf(storedCron).trim().isEmpty());

        // 同一批里必须有猎物：CRON 类型缺 cron 仍然要拒，否则"放宽"是无条件放掉了整道闸
        ReturnT<String> cronMissing = service.add(job("缺 cron 的 CRON", null));
        assertFalse("CRON 类型缺 cron 必须仍然被拒", cronMissing.isSuccess());
        assertTrue(cronMissing.getMsg(), cronMissing.getMsg().contains("Cron表达式不能为空"));
    }

    @Test
    public void 新增FIX_DELAY任务不该要求cron并且可以启动() throws Exception {
        JobInfo delay = job("纯 FIX_DELAY 空 cron", "");
        delay.setTriggerType("FIX_DELAY");
        delay.setFixInterval(3_000L);
        ReturnT<String> added = service.add(delay);
        assertTrue("FIX_DELAY 不读 cron: " + added.getMsg(), added.isSuccess());
        int id = Integer.parseInt(added.getContent());
        assertEquals("FIX_DELAY", row(id).get("trigger_type"));

        ReturnT<String> started = service.start(id);
        assertTrue("没有 cron 的 FIX_DELAY 该能启动: " + started.getMsg(), started.isSuccess());
    }

    @Test
    public void FIX任务给了非空但非法的cron仍然要拒() throws Exception {
        JobInfo fix = job("FIX 带脏 cron", "not a cron at all");
        fix.setTriggerType("FIX_RATE");
        fix.setFixInterval(5_000L);
        ReturnT<String> added = service.add(fix);
        assertFalse("类型不读 cron 不等于可以写进一个坏值", added.isSuccess());
        assertTrue(added.getMsg(), added.getMsg().contains("Cron表达式格式错误"));
    }

    @Test
    public void FIX任务可以清掉遗留的cron() throws Exception {
        int id = insertRaw("带遗留 cron 的 FIX_RATE", GOOD_CRON, "FIX_RATE", 5_000L);

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setJobCron("");
        ReturnT<String> updated = service.update(patch);
        assertTrue("FIX_RATE 清掉不读的 cron 是合法收尾: " + updated.getMsg(), updated.isSuccess());
        Object storedCron = row(id).get("job_cron");
        assertTrue("清空没落库: " + storedCron,
                storedCron == null || String.valueOf(storedCron).trim().isEmpty());

        // 猎物：同一条空串若落在 CRON 类型上，闸门必须还在
        int cronId = addJob("要清空 cron 的 CRON 任务", GOOD_CRON);
        JobInfo clearCron = new JobInfo();
        clearCron.setId(cronId);
        clearCron.setJobCron("");
        assertFalse("CRON 类型清空 cron 必须仍然被拒", service.update(clearCron).isSuccess());
        assertEquals(GOOD_CRON, row(cronId).get("job_cron"));
    }

    @Test
    public void CRON任务的间隔字段仍然可以是0() throws Exception {
        int id = addJob("cron 任务", GOOD_CRON);

        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setTriggerType("CRON");
        patch.setFixInterval(0L);
        assertTrue("CRON 任务不读 fix_interval, 不该被间隔校验挡住", service.update(patch).isSuccess());
        assertEquals("CRON", row(id).get("trigger_type"));
    }

    @Test
    public void 未知触发类型必须被拒绝() throws Exception {
        JobInfo added = job("小写触发类型", GOOD_CRON);
        added.setTriggerType("fix_rate");
        ReturnT<String> addResult = service.add(added);
        assertFalse("大小写不对的触发类型会静默退化成 cron 任务", addResult.isSuccess());

        int id = addJob("改坏触发类型", GOOD_CRON);
        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setTriggerType("CRNN");
        assertFalse(service.update(patch).isSuccess());
        assertEquals("列值不该被写坏: " + row(id).get("trigger_type"), "CRON", row(id).get("trigger_type"));
    }

    @Test
    public void 更新不存在的任务报任务不存在() {
        JobInfo patch = new JobInfo();
        patch.setId(424242);
        patch.setJobDesc("不存在");
        ReturnT<String> updated = service.update(patch);
        assertFalse(updated.isSuccess());
        assertTrue(updated.getMsg(), updated.getMsg().contains("不存在"));
    }

    // ==================== 启停 ====================

    @Test
    public void 启动cron任务置运行态并交给引擎() throws Exception {
        int id = addJob("正常启动", GOOD_CRON);

        ReturnT<String> started = service.start(id);
        assertTrue(started.getMsg(), started.isSuccess());
        assertEquals(1, number(row(id).get("trigger_status")));
        assertEquals("start 成功就必须让引擎装载", 1, trigger.registered.size());
        assertEquals("注册给引擎的必须是同一行任务", id, trigger.registered.get(0).getId());
    }

    @Test
    public void 启动没有配置间隔的FIX_RATE任务必须失败() throws Exception {
        int id = addFixRateJob("FIX_RATE", 0L);

        ReturnT<String> started = service.start(id);
        assertFalse("间隔 0 的 FIX_RATE 引擎不会排期, 报成功等于骗调用方", started.isSuccess());
        assertEquals("失败不该落运行态", 0, number(row(id).get("trigger_status")));
        assertTrue("失败的路径不许把任务交给引擎: " + trigger.registered, trigger.registered.isEmpty());
    }

    @Test
    public void 启动没有配置间隔的FIX_DELAY任务必须失败() throws Exception {
        int id = addFixRateJob("FIX_DELAY", 0L);

        assertFalse(service.start(id).isSuccess());
        assertEquals(0, number(row(id).get("trigger_status")));
        assertTrue(trigger.registered.isEmpty());
    }

    @Test
    public void 配置好间隔的FIX_DELAY任务可以启动() throws Exception {
        int id = addFixRateJob("FIX_DELAY", 30_000L);

        ReturnT<String> started = service.start(id);
        assertTrue(started.getMsg(), started.isSuccess());
        assertEquals(1, number(row(id).get("trigger_status")));
        assertEquals(1, trigger.registered.size());
    }

    /** 引擎的 FIX_RATE/FIX_DELAY 分支只读 fix_interval，不碰 cron；启动判定不该反过来。 */
    @Test
    public void FIX任务的cron不参与启动判定() throws Exception {
        int id = insertRaw("FIX 的坏 cron", BAD_CRON, "FIX_DELAY", 30_000L);

        ReturnT<String> started = service.start(id);
        assertTrue("cron 语法不该拦住 FIX_DELAY 启动: " + started.getMsg(), started.isSuccess());
        assertEquals(1, number(row(id).get("trigger_status")));
        assertEquals(1, trigger.registered.size());
    }

    @Test
    public void 非法的调度过期策略必须被拒绝() throws Exception {
        JobInfo added = job("过期策略写错", GOOD_CRON);
        // 少写 _NOW：枚举匹配不上，引擎会按 DO_NOTHING 走，用户以为配了补偿触发
        added.setMisfireStrategy("FIRE_ONCE");
        ReturnT<String> addResult = service.add(added);
        assertFalse(addResult.getMsg(), addResult.isSuccess());
        assertTrue(addResult.getMsg(), addResult.getMsg().contains("过期策略"));

        int id = addJob("改坏过期策略", GOOD_CRON);
        JobInfo patch = new JobInfo();
        patch.setId(id);
        patch.setMisfireStrategy("do_nothing");
        ReturnT<String> updated = service.update(patch);
        assertFalse("大小写不一致的策略同样要拒绝: " + updated.getMsg(), updated.isSuccess());
        assertEquals("DO_NOTHING", row(id).get("misfire_strategy"));
    }

    @Test
    public void 启动cron非法的任务失败且不交给引擎() throws Exception {
        int id = insertRaw("cron 已损坏", BAD_CRON, "CRON", 0L);

        ReturnT<String> started = service.start(id);
        assertFalse(started.isSuccess());
        assertEquals(0, number(row(id).get("trigger_status")));
        assertTrue(trigger.registered.isEmpty());
    }

    @Test
    public void 启动不存在的任务报任务不存在() {
        ReturnT<String> started = service.start(888888);
        assertFalse(started.isSuccess());
        assertTrue(started.getMsg(), started.getMsg().contains("不存在"));
        assertTrue(trigger.registered.isEmpty());
    }

    @Test
    public void 停止任务清运行态并取消注册() throws Exception {
        int id = addJob("先启后停", GOOD_CRON);
        service.start(id);

        ReturnT<String> stopped = service.stop(id);
        assertTrue(stopped.getMsg(), stopped.isSuccess());
        Map<String, Object> row = row(id);
        assertEquals(0, number(row.get("trigger_status")));
        assertEquals(0, number(row.get("trigger_last_time")));
        assertEquals(0, number(row.get("trigger_next_time")));
        assertEquals("stop 必须通知引擎摘除", java.util.Arrays.asList(id), trigger.cancelled);
    }

    @Test
    public void 运行中的任务不能删除停止后可以() throws Exception {
        int id = addJob("删除保护", GOOD_CRON);
        service.start(id);

        ReturnT<String> blocked = service.delete(id);
        assertFalse("运行中不该被删掉", blocked.isSuccess());
        assertTrue(blocked.getMsg(), blocked.getMsg().contains("停止"));
        assertEquals(1, number(row(id).get("trigger_status")));

        service.stop(id);
        ReturnT<String> deleted = service.delete(id);
        assertTrue(deleted.getMsg(), deleted.isSuccess());
        assertEquals("删除后不该还有行", 0, rows().size());
    }

    @Test
    public void 删除不存在的任务报任务不存在() {
        ReturnT<String> deleted = service.delete(777777);
        assertFalse(deleted.isSuccess());
        assertTrue(deleted.getMsg(), deleted.getMsg().contains("不存在"));
    }

    // ==================== 取证：独立读数 ====================

    private static void assertNullRow(JobInfo dto) {
        if (dto != null) {
            fail("不存在的主键不该读出任务: " + dto.getJobDesc());
        }
    }

    private int addJob(String desc, String cron) {
        ReturnT<String> added = service.add(job(desc, cron));
        assertTrue("新增失败: " + added.getMsg(), added.isSuccess());
        return Integer.parseInt(added.getContent());
    }

    /** 直接落一行指定触发类型的任务，绕过 add() 的校验，好把坏数据摆到盘上。 */
    private int addFixRateJob(String triggerType, long fixInterval) {
        return insertRaw("间隔任务", GOOD_CRON, triggerType, fixInterval);
    }

    private int insertRaw(String desc, String cron, String triggerType, long fixInterval) {
        JobInfoDO d = new JobInfoDO();
        d.setJobGroup(1);
        d.setJobDesc(desc);
        d.setJobCron(cron);
        d.setAuthor("tester");
        d.setTriggerType(triggerType);
        d.setFixInterval(fixInterval);
        d.setMisfireStrategy("DO_NOTHING");
        d.setChildJobId("");
        d.setTriggerStatus(0);
        d.setTriggerLastTime(0L);
        d.setTriggerNextTime(0L);
        d.setExecutorTimeout(0);
        d.setExecutorFailRetryCount(0);
        d.setAddTime(new Date());
        d.setUpdateTime(new Date());
        assertEquals(1, mapper.insert(d));
        return d.getId();
    }

    private void setStatus(int id, int status) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE z_schedule_job_info SET trigger_status = ? WHERE id = ?")) {
            ps.setInt(1, status);
            ps.setInt(2, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    /** 绕开服务把超时/重试摆成非零值：这两列的默认值恰好就是缺陷会写进去的那个 0。 */
    private void setNumbers(int id, int executorTimeout, int retryCount) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE z_schedule_job_info SET executor_timeout = ?, executor_fail_retry_count = ? "
                             + "WHERE id = ?")) {
            ps.setInt(1, executorTimeout);
            ps.setInt(2, retryCount);
            ps.setInt(3, id);
            assertEquals("夹具没找到那一行", 1, ps.executeUpdate());
        }
    }

    private Map<String, Object> row(int id) throws Exception {
        List<Map<String, Object>> all = rows("WHERE id = " + id);
        assertEquals("id=" + id + " 应当恰好一行", 1, all.size());
        return all.get(0);
    }

    private List<Map<String, Object>> rows() throws Exception {
        return rows("");
    }

    /** 键统一转小写：H2 把未加引号的列名大写返回，MySQL 保持原样，测试不该依赖驱动脸色。 */
    private List<Map<String, Object>> rows(String where) throws Exception {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM z_schedule_job_info " + where)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
                }
                out.add(row);
            }
        }
        return out;
    }

    private static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static JobInfo job(String desc, String cron) {
        JobInfo job = new JobInfo();
        job.setJobGroup(1);
        job.setJobDesc(desc);
        job.setJobCron(cron);
        job.setAuthor("tester");
        job.setExecutorHandler("demoHandler");
        job.setExecutorRouteStrategy("ROUND");
        job.setExecutorBlockStrategy("SERIAL_EXECUTION");
        return job;
    }

    /** 只记账不执行：用来钉住"哪些路径不该把任务交给引擎"。 */
    private static final class RecordingTrigger implements JobTriggerService {
        private final List<JobInfo> registered = new ArrayList<JobInfo>();
        private final List<Integer> cancelled = new ArrayList<Integer>();
        private final List<Integer> triggered = new ArrayList<Integer>();

        @Override
        public void registerJob(JobInfo jobInfo) {
            registered.add(jobInfo);
        }

        @Override
        public void cancelJob(int jobId) {
            cancelled.add(jobId);
        }

        @Override
        public void triggerJob(JobInfo jobInfo) {
            triggered.add(jobInfo == null ? 0 : jobInfo.getId());
        }

        @Override
        public void completeJob(int jobId) {
        }

        @Override
        public void killJob(long logId) {
        }
    }
}
