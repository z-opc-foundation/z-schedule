package com.zifang.z.schedule.web.domain;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.core.enums.MisfireStrategyEnum;
import com.zifang.z.schedule.core.enums.TriggerTypeEnum;
import com.zifang.z.schedule.web.domain.entity.JobGroupDO;
import com.zifang.z.schedule.web.domain.entity.JobInfoDO;
import com.zifang.z.schedule.web.domain.entity.JobLeaderDO;
import com.zifang.z.schedule.web.domain.entity.JobLogDO;
import com.zifang.z.schedule.web.domain.entity.JobRegistryDO;
import com.zifang.z.schedule.web.domain.entity.UserDO;
import com.zifang.z.schedule.web.domain.mapper.JobGroupMapper;
import com.zifang.z.schedule.web.domain.mapper.JobInfoMapper;
import com.zifang.z.schedule.web.domain.mapper.JobLeaderMapper;
import com.zifang.z.schedule.web.domain.mapper.JobLogMapper;
import com.zifang.z.schedule.web.domain.mapper.JobRegistryMapper;
import com.zifang.z.schedule.web.domain.mapper.UserMapper;
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
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 建表脚本门禁：{@code _doc/004_sql/z-schedule.sql} 必须能真跑通，并且覆盖六个 DO 的每一列。
 * <p>
 * z-schedule 此前不附带任何建表脚本，唯一在用的参照在 z-opc 仓的文档目录里，而那份脚本比实体少
 * {@code fix_interval} 等四列、还整个缺 {@code z_schedule_user} 表——列对不上时症状是首次访问
 * 抛 "Unknown column"（历史上就是这么炸的），构建期完全不红。这里把脚本变成被数据库执行过的工件：
 * <ol>
 *   <li>脚本原样在 H2(MySQL 模式) 上执行；</li>
 *   <li>每个 DO 的持久化字段（反射得到，snake_case 后）都必须在表的实际列里；</li>
 *   <li>六个 mapper 的真实 CRUD 与 {@link JobLogMapper} 的手写聚合 SQL 都要能在这些表上跑。</li>
 * </ol>
 * 三条的取证来源互相独立：字段来自 class 文件，列来自数据库自己的元数据，SQL 来自脚本原文。
 */
public class ShippedSchemaH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_shipped_schema;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final List<Class<?>> DO_TYPES = Arrays.<Class<?>>asList(
            JobInfoDO.class, JobLogDO.class, JobGroupDO.class,
            JobRegistryDO.class, JobLeaderDO.class, UserDO.class);

    private JdbcDataSource ds;
    private SqlSession session;

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
        cfg.addMapper(JobLogMapper.class);
        cfg.addMapper(JobGroupMapper.class);
        cfg.addMapper(JobRegistryMapper.class);
        cfg.addMapper(JobLeaderMapper.class);
        cfg.addMapper(UserMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    // ==================== 1. 脚本本身 ====================

    @Test
    public void 建表脚本建出六个DO要的表() throws Exception {
        Set<String> tables = tables();
        for (Class<?> doType : DO_TYPES) {
            assertTrue("脚本里没有 " + tableOf(doType) + " 这张表, 实际有 " + tables,
                    tables.contains(tableOf(doType).toUpperCase()));
        }
        assertEquals("表数量应当正好等于 DO 数量, 实际: " + tables, 6, tables.size());
    }

    @Test
    public void 每个DO的持久化字段都能在建表脚本里找到列() throws Exception {
        List<String> missing = new ArrayList<String>();
        for (Class<?> doType : DO_TYPES) {
            Set<String> columns = columnsOf(tableOf(doType));
            for (String column : persistedColumns(doType)) {
                if (!columns.contains(column.toUpperCase())) {
                    missing.add(doType.getSimpleName() + "." + column + " -> "
                            + tableOf(doType) + " 缺列 " + column);
                }
            }
        }
        if (!missing.isEmpty()) {
            fail("DO 与建表脚本不一致(线上症状是首次访问抛 Unknown column):\n  "
                    + join(missing));
        }
    }

    // ==================== 2. 真实读写 ====================

    @Test
    public void 六个mapper的真实增删改查都跑在脚本建出的表上() {
        JobInfoMapper jobInfo = session.getMapper(JobInfoMapper.class);
        JobInfoDO info = new JobInfoDO();
        info.setJobGroup(1);
        info.setJobDesc("每分钟");
        info.setJobCron("0 * * * * ?");
        info.setTriggerType("CRON");
        info.setFixInterval(0L);
        info.setMisfireStrategy("DO_NOTHING");
        info.setChildJobId("");
        info.setTriggerStatus(0);
        info.setTriggerLastTime(0L);
        info.setTriggerNextTime(0L);
        info.setExecutorTimeout(0);
        info.setExecutorFailRetryCount(0);
        info.setAddTime(new Date());
        info.setUpdateTime(new Date());
        assertEquals(1, jobInfo.insert(info));
        assertNotNull("自增主键没回填", info.getId());
        assertNotNull(jobInfo.selectById(info.getId()));
        info.setFixInterval(60_000L);
        info.setTriggerType("FIX_RATE");
        assertEquals(1, jobInfo.updateById(info));
        assertEquals(Long.valueOf(60_000L), jobInfo.selectById(info.getId()).getFixInterval());
        assertEquals(1, jobInfo.selectCount(null).intValue());

        JobLogMapper jobLog = session.getMapper(JobLogMapper.class);
        JobLogDO log = new JobLogDO();
        log.setJobGroup(1);
        log.setJobId(info.getId());
        log.setTriggerTime(new Date());
        log.setTriggerCode(200);
        log.setHandleCode(200);
        log.setAlarmStatus(0);
        log.setExecutorFailRetryCount(0);
        assertEquals(1, jobLog.insert(log));
        assertEquals(1, jobLog.selectCount(null).intValue());
        log.setHandleMsg("ok");
        assertEquals(1, jobLog.updateById(log));
        assertEquals("ok", jobLog.selectById(log.getId()).getHandleMsg());

        JobGroupMapper jobGroup = session.getMapper(JobGroupMapper.class);
        JobGroupDO group = new JobGroupDO();
        group.setAppName("demo-executor");
        group.setTitle("演示执行器");
        group.setAddressType(0);
        group.setOrderNum(0);
        group.setUpdateTime(new Date());
        assertEquals(1, jobGroup.insert(group));
        assertEquals(1, jobGroup.selectCount(null).intValue());

        JobRegistryMapper registry = session.getMapper(JobRegistryMapper.class);
        JobRegistryDO reg = new JobRegistryDO();
        reg.setRegistryGroup("EXECUTOR");
        reg.setRegistryKey("demo-executor");
        reg.setRegistryValue("http://127.0.0.1:9000/");
        reg.setUpdateTime(new Date());
        assertEquals(1, registry.insert(reg));
        assertEquals(1, registry.selectCount(null).intValue());

        UserMapper user = session.getMapper(UserMapper.class);
        UserDO u = new UserDO();
        u.setUsername("schema-probe");
        u.setPassword("0123456789abcdef0123456789abcdef");
        u.setRole("NORMAL");
        u.setPermission("");
        u.setAddTime(new Date());
        u.setUpdateTime(new Date());
        assertEquals(1, user.insert(u));
        assertEquals(1, user.selectCount(null).intValue());

        JobLeaderMapper leader = session.getMapper(JobLeaderMapper.class);
        // 脚本自带 id=1 的种子行；这里验证它真的在，并能被 update 抢占
        JobLeaderDO seed = leader.selectById(JobLeaderDO.SINGLETON_ID);
        assertNotNull("脚本没种下 id=" + JobLeaderDO.SINGLETON_ID + " 的单行, 选主会停摆", seed);
        seed.setOwner("node-a");
        seed.setHost("127.0.0.1");
        seed.setExpireTime(new Date(System.currentTimeMillis() + 60_000L));
        assertEquals(1, leader.updateById(seed));
        assertEquals("node-a", leader.selectById(JobLeaderDO.SINGLETON_ID).getOwner());

        assertEquals(1, jobInfo.deleteById(info.getId()));
        assertEquals(1, jobLog.deleteById(log.getId()));
        assertEquals(1, jobGroup.deleteById(group.getId()));
        assertEquals(1, registry.deleteById(reg.getId()));
        assertEquals(1, user.deleteById(u.getId()));
    }

    /**
     * 手写聚合 SQL 引用的列名也必须在脚本里存在——它不走 MP 的列映射，漂移不会被 CRUD 抓到。
     * <p>
     * 断言走 {@link JobLogServiceImpl} 而不是裸 Map：{@code statsBetween} 的别名大小写随驱动而变
     * (H2 把 {@code total} 变成 {@code TOTAL})，服务层做了不区分大小写的取值，这里连那层一起验。
     */
    @Test
    public void 手写聚合SQL也跑在脚本建出的表上() throws Exception {
        JobLogMapper jobLog = session.getMapper(JobLogMapper.class);
        Date now = new Date();
        for (int i = 0; i < 2; i++) {
            JobLogDO log = new JobLogDO();
            log.setJobGroup(1);
            log.setJobId(100 + i);
            log.setTriggerTime(now);
            log.setTriggerCode(200);
            log.setHandleCode(i == 0 ? 200 : 500);
            log.setAlarmStatus(0);
            log.setExecutorFailRetryCount(0);
            jobLog.insert(log);
        }
        Calendar end = Calendar.getInstance();
        end.add(Calendar.DAY_OF_MONTH, 1);
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_MONTH, -1);

        JobLogServiceImpl service = new JobLogServiceImpl();
        Field mapper = JobLogServiceImpl.class.getDeclaredField("jobLogMapper");
        mapper.setAccessible(true);
        mapper.set(service, jobLog);

        JobLogService.Stats stats = service.statsBetween(start.getTime(), end.getTime());
        assertEquals(2L, stats.getTotal());
        assertEquals(1L, stats.getSuccess());

        Map<String, JobLogService.Stats> byDay = service.dailyStatsSince(start.getTime());
        assertEquals("按自然日聚合应当只落一天: " + byDay.keySet(), 1, byDay.size());
        JobLogService.Stats day = byDay.values().iterator().next();
        assertEquals(2L, day.getTotal());
        assertEquals(1L, day.getSuccess());
    }

    /**
     * 枚举值必须存得进列：{@code trigger_type} 写成 varchar(8) 时 {@code FIX_DELAY}(9 字节)存不下，
     * 而列名对齐检查完全看不出来——列是在的，只是太短。严格模式报错、非严格模式截断成
     * {@code FIX_DELA}，之后枚举匹配不上，任务被当成 cron 处理，FIX_DELAY 就再也没生效过。
     */
    @Test
    public void 枚举值必须原样存得进脚本建出的列() throws Exception {
        List<String> codes = new ArrayList<String>();
        for (TriggerTypeEnum type : TriggerTypeEnum.values()) {
            codes.add(type.getCode());
        }
        List<String> strategies = new ArrayList<String>();
        for (MisfireStrategyEnum strategy : MisfireStrategyEnum.values()) {
            strategies.add(strategy.getCode());
        }

        try (Connection c = ds.getConnection()) {
            for (String code : codes) {
                for (String strategy : strategies) {
                    insertRawJob(c, "枚举列宽探针 " + code + "/" + strategy, code, strategy);
                    Map<String, Object> read = readBack(c, "枚举列宽探针 " + code + "/" + strategy);
                    assertEquals("trigger_type 被改动: " + code, code, read.get("trigger_type"));
                    assertEquals("misfire_strategy 被改动: " + strategy, strategy,
                            read.get("misfire_strategy"));
                }
            }
        }
        assertTrue("两个枚举都不能是空的, 否则这条检查是空跑", codes.size() >= 2 && strategies.size() >= 2);
    }

    // ==================== 3. 门禁自己要能判红 ====================

    /**
     * 阳性对照：把脚本里的 {@code fix_interval} 一列抹掉，上面那条对齐检查必须点名它。
     * 没有这一例，"全绿"就分不清是脚本对还是门禁根本读不到列。
     */
    @Test
    public void 脚本少一列时对齐检查必须点名那一列() throws Exception {
        List<String> statements = ShippedSqlScript.statements();
        int jobInfoIdx = indexOfJobInfo(statements);
        String jobInfoDdl = statements.get(jobInfoIdx);

        StringBuilder kept = new StringBuilder(jobInfoDdl.length());
        int hit = 0;
        for (String line : jobInfoDdl.split("\n")) {
            if (line.trim().startsWith("`fix_interval`")) {
                hit++;
                continue;
            }
            kept.append(line).append('\n');
        }
        assertEquals("脚本里 fix_interval 的列行应当正好 1 行, 对照的抹法要跟着改", 1, hit);
        assertFalse("抹列没生效, 对照无效", kept.toString().contains("fix_interval"));

        List<String> mutilated = new ArrayList<String>(statements);
        mutilated.set(jobInfoIdx, kept.toString());

        JdbcDataSource other = new JdbcDataSource();
        other.setURL("jdbc:h2:mem:zschedule_shipped_missing;MODE=MySQL;DB_CLOSE_DELAY=-1");
        other.setUser("sa");
        Connection c = other.getConnection();
        try {
            ShippedSqlScript.apply(c, mutilated);
            Set<String> columns = columnsOf(c, "z_schedule_job_info");
            assertTrue("前提: 其余列都在", columns.contains("TRIGGER_TYPE"));
            assertFalse("抹掉的列竟然还在", columns.contains("FIX_INTERVAL"));
            List<String> missing = new ArrayList<String>();
            for (String column : persistedColumns(JobInfoDO.class)) {
                if (!columns.contains(column.toUpperCase())) {
                    missing.add(column);
                }
            }
            assertEquals("对齐检查点名的缺列", Arrays.asList("fix_interval"), missing);
        } finally {
            c.close();
        }
    }

    /** 脚本必须真含六张表——否则上面所有断言都在空集上自说自话。 */
    @Test
    public void 脚本本身不是空文件() throws Exception {
        List<String> statements = ShippedSqlScript.statements();
        assertEquals("建表脚本应当是 6 个 CREATE + 1 个种子行, 实际: " + statements, 7, statements.size());
        int creates = 0;
        for (String stmt : statements) {
            if (stmt.startsWith("CREATE TABLE")) {
                creates++;
            }
        }
        assertEquals(6, creates);
    }

    // ==================== 工具 ====================

    /** 走裸 SQL 而不是 mapper：列宽出问题时，要让驱动的错误直接现形，不经 MP 转手。 */
    private static void insertRawJob(Connection c, String jobDesc, String triggerType,
                                     String misfireStrategy) throws SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO z_schedule_job_info (job_group, job_desc, job_cron, trigger_type,"
                        + " misfire_strategy, trigger_status, executor_timeout, executor_fail_retry_count)"
                        + " VALUES (1, ?, '0 * * * * ?', ?, ?, 0, 0, 0)")) {
            ps.setString(1, jobDesc);
            ps.setString(2, triggerType);
            ps.setString(3, misfireStrategy);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static Map<String, Object> readBack(Connection c, String jobDesc) throws SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT trigger_type, misfire_strategy FROM z_schedule_job_info WHERE job_desc = ?")) {
            ps.setString(1, jobDesc);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue("按描述读不回刚插入的行", rs.next());
                Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                row.put("trigger_type", rs.getString(1));
                row.put("misfire_strategy", rs.getString(2));
                assertFalse("同一描述读回了多行", rs.next());
                return row;
            }
        }
    }

    private static int indexOfJobInfo(List<String> statements) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).contains("z_schedule_job_info")) {
                return i;
            }
        }
        throw new IllegalStateException("脚本里没有 z_schedule_job_info");
    }

    private Set<String> tables() throws SQLException {
        try (Connection c = ds.getConnection()) {
            return tables(c);
        }
    }

    private static Set<String> tables(Connection c) throws SQLException {
        Set<String> out = new LinkedHashSet<String>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                     + " WHERE TABLE_SCHEMA='PUBLIC'")) {
            while (rs.next()) {
                out.add(rs.getString(1).toUpperCase());
            }
        }
        return out;
    }

    private Set<String> columnsOf(String table) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return columnsOf(c, table);
        }
    }

    private static Set<String> columnsOf(Connection c, String table) throws SQLException {
        Set<String> out = new LinkedHashSet<String>();
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA='PUBLIC' AND UPPER(TABLE_NAME)=UPPER(?)")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1).toUpperCase());
                }
            }
        }
        assertFalse("表 " + table + " 一条列元数据都没读到(表不存在?)", out.isEmpty());
        return out;
    }

    /** DO 上会被 MP 写进 SQL 的字段 → snake_case 列名。transient/static 除外。 */
    private static List<String> persistedColumns(Class<?> doType) {
        List<String> out = new ArrayList<String>();
        for (Field f : doType.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || "serialVersionUID".equals(f.getName())) {
                continue;
            }
            out.add(toSnakeCase(f.getName()));
        }
        assertTrue(doType.getSimpleName() + " 一个字段都没反射到", !out.isEmpty());
        return out;
    }

    private static String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String tableOf(Class<?> doType) {
        com.baomidou.mybatisplus.annotation.TableName meta =
                doType.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class);
        assertNotNull(doType.getSimpleName() + " 没有 @TableName", meta);
        return meta.value();
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            sb.append(item).append("\n  ");
        }
        return sb.toString();
    }
}
