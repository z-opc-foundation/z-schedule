package com.zifang.z.schedule.web.cluster;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.web.domain.entity.JobLeaderDO;
import com.zifang.z.schedule.web.domain.mapper.JobLeaderMapper;
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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * LeaderElector 的集群语义验证（真实数据库，两个选举器抢同一行）.
 * <p>
 * 抢占/续约/降级全靠 UPDATE 的 WHERE 条件判定，用假 mapper 无法表达"条件匹配了几行"，
 * 所以这里跑 H2（连接串与 admin dev profile 一致）。
 * <p>
 * 表里没有 id=1 那一行时，所有 UPDATE 影响 0 行 ⇒ 整个集群永远选不出主、
 * 调度停摆且没有任何日志。种子行原本只由建表脚本提供，因此单独钉了两条用例。
 */
public class LeaderElectorH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_leader;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private final List<SqlSession> opened = new ArrayList<SqlSession>();
    private JdbcDataSource ds;
    private SqlSessionFactory factory;

    @Before
    public void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            s.execute("CREATE TABLE z_schedule_job_leader ("
                    + "id INT NOT NULL PRIMARY KEY, owner VARCHAR(64), host VARCHAR(64), expire_time TIMESTAMP)");
        }
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("h2", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(cfg, GlobalConfigUtils.defaults());
        cfg.addMapper(JobLeaderMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
    }

    @After
    public void tearDown() {
        for (SqlSession s : opened) {
            s.close();
        }
        opened.clear();
    }

    /** 一个选举器 = 一个节点：各自持有独立的 session 和 mapper，共享同一张表. */
    private LeaderElector newNode() throws Exception {
        SqlSession session = factory.openSession(true);
        opened.add(session);
        LeaderElector node = new LeaderElector();
        Field f = LeaderElector.class.getDeclaredField("jobLeaderMapper");
        f.setAccessible(true);
        f.set(node, session.getMapper(JobLeaderMapper.class));
        return node;
    }

    private JobLeaderDO row() throws Exception {
        LeaderElector reader = newNode();
        return reader.currentLeader();
    }

    private void seed(String owner, long expireMillisFromNow) throws Exception {
        try (Connection c = ds.getConnection()) {
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM z_schedule_job_leader");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO z_schedule_job_leader (id, owner, host, expire_time) VALUES (1,?,?,?)")) {
                ps.setString(1, owner);
                ps.setString(2, "seeded-host");
                if (expireMillisFromNow == Long.MIN_VALUE) {
                    ps.setNull(3, java.sql.Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis() + expireMillisFromNow));
                }
                ps.executeUpdate();
            }
        }
    }

    @Test
    public void 表里没有单行记录时任何节点都选不出主() throws Exception {
        LeaderElector node = newNode();

        node.elect();

        assertFalse("没有可锁的行时不得自称 Leader", node.isLeader());
        assertNull(row());
    }

    @Test
    public void 启动会补上缺失的单行记录并让节点随后选上主() throws Exception {
        LeaderElector node = newNode();

        node.init();
        node.elect();

        JobLeaderDO after = row();
        assertNotNull("init 必须保证 id=1 这一行存在，否则调度中心永久空转", after);
        assertEquals(Integer.valueOf(1), after.getId());
        assertTrue("补行之后第一次 elect 就应当抢到", node.isLeader());
        assertEquals(node.getInstanceId(), after.getOwner());
    }

    @Test
    public void 启动补行不得顶掉现任leader() throws Exception {
        seed("incumbent-instance", 60_000L);
        LeaderElector newcomer = newNode();

        newcomer.init();

        JobLeaderDO after = row();
        assertEquals("表里已有行时 init 不能覆盖现任 owner", "incumbent-instance", after.getOwner());
        assertFalse(newcomer.isLeader());
    }

    @Test
    public void 未过期的现任可以挡住其他节点() throws Exception {
        LeaderElector first = newNode();
        first.init();
        first.elect();
        assertTrue(first.isLeader());

        LeaderElector second = newNode();
        second.elect();

        assertFalse("任期未满时第二个节点不能抢", second.isLeader());
        assertEquals(first.getInstanceId(), row().getOwner());
    }

    @Test
    public void 前任过期后其他节点可接管() throws Exception {
        LeaderElector stale = newNode();
        stale.init();
        stale.elect();
        String staleId = stale.getInstanceId();

        // 把任期拨到过去，模拟节点崩溃后 TTL 到期
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE z_schedule_job_leader SET expire_time = ? WHERE id = 1")) {
            ps.setTimestamp(1, new java.sql.Timestamp(System.currentTimeMillis() - 60_000L));
            assertEquals(1, ps.executeUpdate());
        }

        LeaderElector survivor = newNode();
        survivor.elect();

        assertTrue("过期后应可接管", survivor.isLeader());
        JobLeaderDO after = row();
        assertEquals(survivor.getInstanceId(), after.getOwner());
        assertFalse("接管方要留下自己的 host 便于排障", "seeded-host".equals(after.getHost()));
        assertFalse(staleId.equals(after.getOwner()));

        // 旧主并不"知道"自己被换掉，只有下一次续约失败才降级
        stale.elect();
        assertFalse("旧主续约拿到 0 行后必须降级", stale.isLeader());
    }

    @Test
    public void 续约只能由现任完成且只推后过期时间() throws Exception {
        LeaderElector node = newNode();
        node.init();
        node.elect();

        java.util.Date before = row().getExpireTime();
        assertNotNull(before);

        node.elect(); // isLeader=true 分支 => renew

        JobLeaderDO after = row();
        assertTrue("续约应把 expire_time 往后推", after.getExpireTime().after(before));
        assertEquals("续约不得改 owner", node.getInstanceId(), after.getOwner());
        assertTrue(node.isLeader());
    }

    @Test
    public void 主被抢走后续约失败要自动降级() throws Exception {
        LeaderElector node = newNode();
        node.init();
        node.elect();
        assertTrue(node.isLeader());

        seed("whoever-took-over", 60_000L); // 模拟被别的路径接管

        node.elect();

        assertFalse("renew 影响 0 行时必须降级为 follower", node.isLeader());
        assertEquals("降级不能把新主的行改掉", "whoever-took-over", row().getOwner());
    }

    @Test
    public void 主动让位只清自己那份且别人能立刻接上() throws Exception {
        LeaderElector leader = newNode();
        leader.init();
        leader.elect();

        LeaderElector other = newNode();
        other.stepDown(); // 不是主，不该动表
        assertEquals(leader.getInstanceId(), row().getOwner());

        leader.stepDown();
        assertFalse(leader.isLeader());
        JobLeaderDO released = row();
        assertNull("让位要清掉 owner", released.getOwner());
        assertNull("让位要清掉任期，否则别人得等 TTL", released.getExpireTime());

        other.elect();
        assertTrue("让位后其他节点应立刻可抢，而不是等 30 秒", other.isLeader());
    }

    /**
     * 让位时的 owner 条件不是摆设：旧主在被接管之后、下一次续约之前仍然自认为是主，
     * 此时它 stepDown 绝不能把新主的行清掉（否则会引发第三次选举）。
     */
    @Test
    public void 被接管的旧主让位时不得清掉新主() throws Exception {
        LeaderElector stale = newNode();
        stale.init();
        stale.elect();

        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE z_schedule_job_leader SET expire_time = ? WHERE id = 1")) {
            ps.setTimestamp(1, new java.sql.Timestamp(System.currentTimeMillis() - 60_000L));
            assertEquals(1, ps.executeUpdate());
        }
        LeaderElector taken = newNode();
        taken.elect();
        assertTrue(taken.isLeader());
        assertTrue("旧主此时还自认为是主（split brain 窗口）", stale.isLeader());

        stale.stepDown();

        assertEquals("旧主不能让新主掉线", taken.getInstanceId(), row().getOwner());
        assertNotNull("旧主更不能把任期清空", row().getExpireTime());
    }
}
