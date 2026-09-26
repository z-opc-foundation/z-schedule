package com.zifang.z.schedule.web.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.model.User;
import com.zifang.z.schedule.web.auth.LoginSession;
import com.zifang.z.schedule.web.auth.LoginSessionStore;
import com.zifang.z.schedule.web.domain.mapper.UserMapper;
import com.zifang.z.schedule.web.filter.TokenAuthFilter;
import com.zifang.z.schedule.web.service.UserService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 用户读写链路，跑在真实 H2 上。
 * <p>
 * 建表照 z-schedule 唯一的用户表脚本（z-opc {@code _doc/006_troubleshooting/z-schedule-schema-migration.sql}）：
 * {@code username} 上有 {@code UNIQUE KEY uk_username}。这条唯一键是本层语义的一部分——
 * 服务里"先查再插"的检查只是快路径，真正裁决重名的是数据库，所以假 mapper 记不出这里的差异。
 * <p>
 * 读回一律走裸 JDBC：要证的是"哪一列被写成了什么"，经过 DoMapper 读回来的值已经是被测代码加工过的。
 */
public class UserServiceImplH2Test {

    private static final String URL = "jdbc:h2:mem:zschedule_user;MODE=MySQL;DB_CLOSE_DELAY=-1";

    /** UTF-8 下 "123456" 的 MD5，固定字面量：不这么写就无法区分"按 UTF-8 取字节"和"按平台默认字符集取字节"。 */
    private static final String MD5_123456 = "e10adc3949ba59abbe56e057f20f883e";

    /** 同上，"调度中心口令" 的 UTF-8 MD5；含非 ASCII 字符，专门用来盯字符集。 */
    private static final String MD5_CJK = "e375bf5c6ff9ea3fe04bc3a2b115febf";

    private JdbcDataSource ds;
    private SqlSession session;
    private UserService service;
    private LoginSessionStore sessionStore;

    @Before
    public void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL(URL);
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            s.execute("CREATE TABLE z_schedule_user ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "username VARCHAR(64) NOT NULL DEFAULT '',"
                    + "password VARCHAR(128) NOT NULL DEFAULT '',"
                    + "role VARCHAR(32) NOT NULL DEFAULT '',"
                    + "permission VARCHAR(512) NOT NULL DEFAULT '',"
                    + "add_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "CONSTRAINT uk_username UNIQUE (username))");
        }

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("h2", new JdbcTransactionFactory(), ds));
        GlobalConfigUtils.setGlobalConfig(cfg, GlobalConfigUtils.defaults());
        cfg.addMapper(UserMapper.class);

        session = new MybatisSqlSessionFactoryBuilder().build(cfg).openSession(true);
        UserMapper mapper = session.getMapper(UserMapper.class);

        UserServiceImpl impl = new UserServiceImpl();
        // 同一个 store 既是签发方也是校验方：这里若各建一张表，链路测试就会假红
        sessionStore = new LoginSessionStore();
        inject(impl, "userMapper", mapper);
        inject(impl, "sessionStore", sessionStore);
        service = impl;
    }

    @After
    public void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    // ==================== 新增 ====================

    @Test
    public void 新增用户存的是散列而不是明文() throws Exception {
        User u = user("alice", "123456");
        ReturnT<String> r = service.add(u);
        assertTrue(r.getMsg(), r.isSuccess());

        Map<String, Object> row = readRow("alice");
        assertEquals(MD5_123456, row.get("password"));
    }

    @Test
    public void 非ASCII密码按UTF8取字节做散列() throws Exception {
        service.add(user("bob", "调度中心口令"));

        Map<String, Object> row = readRow("bob");
        assertEquals("密码散列必须与 JVM 默认字符集无关，否则同一账号换台机器就登不上",
                MD5_CJK, row.get("password"));
        assertTrue(service.login("bob", "调度中心口令").isSuccess());
    }

    @Test
    public void 新增用户补齐角色与两个时间戳() throws Exception {
        service.add(user("carol", "123456"));

        Map<String, Object> row = readRow("carol");
        assertEquals("NORMAL", row.get("role"));
        assertNotNull("add_time 没盖章", row.get("add_time"));
        assertNotNull("update_time 没盖章", row.get("update_time"));
    }

    @Test
    public void 空用户名或空密码不能建账号也不留半行() throws Exception {
        assertFalse(service.add(user(null, "123456")).isSuccess());
        assertFalse(service.add(user("  ", "123456")).isSuccess());
        assertFalse(service.add(user("dave", null)).isSuccess());
        assertFalse(service.add(user("dave", "   ")).isSuccess());
        assertEquals("校验失败的请求不能已经落库", 0, countRows());
    }

    @Test
    public void 重名用户建不出来() throws Exception {
        service.add(user("erin", "123456"));
        ReturnT<String> dup = service.add(user("erin", "654321"));

        assertFalse(dup.getMsg(), dup.isSuccess());
        assertEquals(1, countRows());
    }

    // ==================== 登录 ====================

    @Test
    public void 登录用明文密码比对库里的散列() throws Exception {
        service.add(user("henry", "123456"));

        ReturnT<String> ok = service.login("henry", "123456");
        assertTrue(ok.getMsg(), ok.isSuccess());
        // content 曾经过= 用户名，而用户名在 /user/list 里公开可读 ⇒ 拿它当凭证等于人人已登录
        assertFalse("凭证绝不能是可公开读到的用户名", "henry".equals(ok.getContent()));

        LoginSession issued = sessionStore.resolve(ok.getContent());
        assertNotNull("登录必须换出一个服务端认识的会话", issued);
        assertEquals("henry", issued.getUsername());
        assertEquals("身份要绑到库里那一行", 1, issued.getUserId());

        assertFalse("密码错误要失败", service.login("henry", "wrong-pass").isSuccess());
        assertFalse("不存在的用户要失败", service.login("nobody", "123456").isSuccess());
    }

    @Test
    public void 登录失败一条会话都不签发() throws Exception {
        service.add(user("isis", "123456"));

        int before = sessionStore.size();
        service.login("isis", "wrong-pass");
        service.login("ghost", "123456");
        service.login(null, "123456");

        assertEquals("验不过的凭据不能换来身份", before, sessionStore.size());
    }

    @Test
    public void 令牌能过过滤器而登出后过不了() throws Exception {
        // 走真实进程边界：签发方（服务）与校验方（过滤器）之间只隔这个 store。
        // 两侧各自一张表的话，这里就是"登录成功但每个请求 403"。
        service.add(user("jane", "123456"));
        String token = service.login("jane", "123456").getContent();

        ScheduleProperties props = new ScheduleProperties();
        props.setAccessToken("shared-secret");
        TokenAuthFilter filter = new TokenAuthFilter(props, sessionStore);

        assertTrue("登录换来的令牌就是凭证", passes(filter, token, "/jobinfo/list"));
        assertTrue("注销幂等成功", service.logout(token).isSuccess());
        assertFalse("注销后同一把令牌不能再进门", passes(filter, token, "/jobinfo/list"));
        // 阳性对照：重新登录换来的令牌仍能进门，说明上面那条红不是"过滤器从此全拦"
        String again = service.login("jane", "123456").getContent();
        assertTrue("重新登录必须能换到新会话", passes(filter, again, "/jobinfo/list"));
    }

    @Test
    public void 改角色会把该用户的旧会话踢下线() throws Exception {
        int id = Integer.parseInt(service.add(user("kate", "123456")).getContent());
        String token = service.login("kate", "123456").getContent();
        assertFalse(sessionStore.resolve(token).isAdmin());

        User promote = new User();
        promote.setId(id);
        promote.setRole("ADMIN");
        ReturnT<String> updated = service.update(promote);
        assertTrue(updated.getMsg(), updated.isSuccess());

        assertNull("带着旧角色的会话必须失效，提权也要重新登录", sessionStore.resolve(token));
    }

    @Test
    public void 改分组会把该用户的旧会话踢下线() throws Exception {
        int id = Integer.parseInt(service.add(user("nina", "123456")).getContent());
        String token = service.login("nina", "123456").getContent();
        assertNotNull(token);

        User scoped = new User();
        scoped.setId(id);
        scoped.setPermission("3");
        ReturnT<String> updated = service.update(scoped);
        assertTrue(updated.getMsg(), updated.isSuccess());
        assertNull("收口一个账号不能等他 30 min 自己过期：permission 现在和 role 一样是会话里带着的身份",
                sessionStore.resolve(token));

        // 阳性对照：把同一列改成它**已经有**的值不算变更，不该把人踢下线
        String fresh = service.login("nina", "123456").getContent();
        User same = new User();
        same.setId(id);
        same.setPermission("3");
        assertTrue(service.update(same).isSuccess());
        assertNotNull("没有变更却作废会话，等于每次点保存都把人踢一次", sessionStore.resolve(fresh));
        assertEquals("3", sessionStore.resolve(fresh).getPermission());
    }

    @Test
    public void 删账号会把他的会话一并撤销() throws Exception {
        int id = Integer.parseInt(service.add(user("liam", "123456")).getContent());
        String token = service.login("liam", "123456").getContent();
        service.add(user("mia", "123456"));
        String someoneElse = service.login("mia", "123456").getContent();

        ReturnT<String> deleted = service.delete(id);
        assertTrue(deleted.getMsg(), deleted.isSuccess());

        assertNull("账号都没了，会话还活着就是隐身入口", sessionStore.resolve(token));
        assertNotNull("不能顺手把别人也踢掉", sessionStore.resolve(someoneElse));
    }

    /**
     * 把一次真实请求打在过滤器上，看它放不放行。
     * <p>
     * 只给 {@code getHeader}/{@code getServletPath}/{@code getMethod} 之外的一律回默认值：
     * 这条链上 TokenAuthFilter 只读这几个。
     */
    private static boolean passes(final TokenAuthFilter filter, final String token, final String path)
            throws Exception {
        final boolean[] reached = {false};
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                UserServiceImplH2Test.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getServletPath":
                            return path;
                        case "getHeader":
                            return "X-Access-Token".equals(args[0]) ? token : null;
                        case "getMethod":
                            return "POST";
                        default:
                            return null;
                    }
                });
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                UserServiceImplH2Test.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    // 被拒的那次会写 403 响应体：没有 writer 的替身会让"应当被拒"这条断言炸成 NPE
                    if ("getWriter".equals(method.getName())) {
                        return new PrintWriter(new StringWriter());
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE
                            : method.getReturnType() == int.class ? Integer.valueOf(0) : null;
                });
        filter.doFilter(request, response, (req, res) -> reached[0] = true);
        return reached[0];
    }

    /**
     * 建表脚本里那条种子账号写的是明文 {@code '123456'}，而登录把输入做 MD5 后再比列值——
     * 也就是说按脚本初始化出来的 admin 永远登不进去。这里钉住"代码不接受明文列值"这个事实：
     * 要救那个账号得改数据（把列改成散列），不能靠放宽登录校验。
     */
    @Test
    public void 库里存明文密码的账号登不进来() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO z_schedule_user (username, password, role, permission) "
                    + "VALUES ('admin', '123456', 'admin', '')");
        }
        assertFalse(service.login("admin", "123456").isSuccess());
    }

    @Test
    public void 登录参数为空时不能去查库也不能成功() throws Exception {
        assertFalse(service.login(null, "123456").isSuccess());
        assertFalse(service.login("  ", "123456").isSuccess());
        assertFalse(service.login("anyone", null).isSuccess());
        assertFalse(service.login("anyone", "  ").isSuccess());
    }

    // ==================== 更新 ====================

    @Test
    public void 更新不能把用户名改成别人已占用的() throws Exception {
        int ivanId = Integer.parseInt(service.add(user("ivan", "123456")).getContent());
        service.add(user("judy", "123456"));

        User change = new User();
        change.setId(ivanId);
        change.setUsername("judy");
        ReturnT<String> r;
        try {
            r = service.update(change);
        } catch (RuntimeException e) {
            fail("重名更新把数据库异常抛给了调用方（HTTP 500）而不是报参数冲突: " + e);
            return;
        }
        assertFalse(r.getMsg(), r.isSuccess());
        assertEquals("冲突的更新不能改动任何行", "ivan", readRow("ivan").get("username"));
        assertEquals(2, countRows());
    }

    @Test
    public void 更新不能把用户名改成空白() throws Exception {
        int id = Integer.parseInt(service.add(user("karl", "123456")).getContent());

        User blank = new User();
        blank.setId(id);
        blank.setUsername("   ");
        ReturnT<String> r = service.update(blank);

        assertFalse("清空用户名会让这个账号再也无法登录: " + r.getMsg(), r.isSuccess());
        assertEquals("karl", readRow("karl").get("username"));
    }

    @Test
    public void 更新自己占用的用户名不算重名() throws Exception {
        int id = Integer.parseInt(service.add(user("lena", "123456")).getContent());

        User same = new User();
        same.setId(id);
        same.setUsername("lena");
        same.setRole("ADMIN");
        ReturnT<String> r = service.update(same);
        assertTrue(r.getMsg(), r.isSuccess());

        assertEquals("ADMIN", readRow("lena").get("role"));
    }

    @Test
    public void 不传密码就保留原散列() throws Exception {
        int id = Integer.parseInt(service.add(user("mike", "123456")).getContent());

        User u = new User();
        u.setId(id);
        u.setPermission("1,2,3");
        assertTrue(service.update(u).isSuccess());

        Map<String, Object> row = readRow("mike");
        assertEquals(MD5_123456, row.get("password"));
        assertEquals("1,2,3", row.get("permission"));
    }

    @Test
    public void 传了新密码才换散列() throws Exception {
        int id = Integer.parseInt(service.add(user("nina", "123456")).getContent());

        User u = new User();
        u.setId(id);
        u.setPassword("654321");
        assertTrue(service.update(u).isSuccess());

        assertFalse(service.login("nina", "123456").isSuccess());
        assertTrue(service.login("nina", "654321").isSuccess());
    }

    @Test
    public void 更新或新建不存在的用户都报失败() throws Exception {
        User ghost = new User();
        ghost.setId(9999);
        ghost.setRole("ADMIN");
        assertFalse(service.update(ghost).isSuccess());

        User noId = new User();
        noId.setUsername("no-id");
        assertFalse(service.update(noId).isSuccess());

        assertEquals(0, countRows());
    }

    // ==================== 读路径不能带出凭证 ====================

    @Test
    public void 用户列表不能带出密码散列() throws Exception {
        service.add(user("olive", "123456"));

        List<User> all = service.getAll();
        assertEquals(1, all.size());
        assertNull("GET /user/list 会把每个账号的密码散列吐给调用方", all.get(0).getPassword());
        assertEquals("olive", all.get(0).getUsername());
        assertEquals("NORMAL", all.get(0).getRole());
    }

    @Test
    public void 按主键和按用户名查也不能带出密码散列() throws Exception {
        int id = Integer.parseInt(service.add(user("quinn", "123456")).getContent());

        assertNull(service.getById(id).getPassword());
        assertNull(service.getByUsername("quinn").getPassword());
        assertNull(service.getByUsername("not-here"));
    }

    // ==================== 删除 ====================

    @Test
    public void 删除存在的用户后行消失() throws Exception {
        int id = Integer.parseInt(service.add(user("rose", "123456")).getContent());

        assertTrue(service.delete(id).isSuccess());
        assertEquals(0, countRows());
    }

    @Test
    public void 删除不存在的用户报失败而不是假装成功() throws Exception {
        service.add(user("sam", "123456"));

        assertFalse(service.delete(4242).isSuccess());
        assertEquals(1, countRows());
    }

    // ==================== 裸 JDBC 读数 ====================

    private static User user(String username, String password) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(password);
        return u;
    }

    private Map<String, Object> readRow(String username) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM z_schedule_user WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("库里没有 username=" + username + " 的行");
                }
                return toMap(rs);
            }
        }
    }

    private int countRows() throws Exception {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM z_schedule_user")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Map<String, Object> toMap(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            row.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
        }
        return row;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
