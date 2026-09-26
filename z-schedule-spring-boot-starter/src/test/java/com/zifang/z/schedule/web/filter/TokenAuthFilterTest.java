package com.zifang.z.schedule.web.filter;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.web.auth.LoginSession;
import com.zifang.z.schedule.web.auth.LoginSessionStore;
import com.zifang.z.schedule.web.config.ZScheduleAutoConfiguration;
import com.zifang.z.schedule.web.domain.entity.UserDO;
import org.junit.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link TokenAuthFilter} 的鉴权边界。
 * <p>
 * 这个过滤器守的是整个调度中心：既能触发/回报任务执行，也能建任务、删任务、改用户、
 * 看别人的执行日志，所以最值得钉的是"什么形状的请求会走到校验"。旧实现有两层漏：
 * 一是只拦 {@code /executor/*}，管理面全裸；二是过滤器注册在 {@code /executor/*} 上，
 * 于是拿 {@code getRequestURI()} 前缀判断（带 context-path、不解码）时，
 * {@code context-path=/schedule} 部署下的执行器回调也会整个跳过 token 校验。
 */
public class TokenAuthFilterTest {

    private final RecordingChain chain = new RecordingChain();
    private final RecordingResponse response = new RecordingResponse();

    // ---- 哪些路径要鉴权 ----

    @Test
    public void 带contextPath的部署也必须鉴权() throws Exception {
        // Tomcat 语义:requestURI 含 context-path，servletPath+pathInfo 才是映射后的路径
        filter("secret").doFilter(request("", "/schedule/executor/callback", "/schedule",
                "/executor/callback", null, null), response.proxy(), chain);

        assertFalse("context-path 不得让执行器接口绕过 token", chain.called);
        assertEquals(403, response.status);
    }

    @Test
    public void 编码过的路径不得绕过鉴权() throws Exception {
        filter("secret").doFilter(request("", "/%65xecutor/callback", "", "/executor/callback",
                null, null), response.proxy(), chain);

        assertFalse(chain.called);
        assertEquals(403, response.status);
    }

    @Test
    public void 管理面读写口同样要token() throws Exception {
        // 旧实现只拦 /executor/*：配了 accessToken 之后，建任务/删任务/看日志/改用户/
        // dashboard/actuator 一个都不经过过滤器，鉴权等于零。
        for (String path : new String[]{"/jobinfo/list", "/jobinfo/add", "/jobinfo/remove",
                "/joblog/list", "/jobgroup/list", "/glue/save", "/user/list", "/user/remove",
                "/dashboard/stats", "/actuator/health"}) {
            RecordingChain c = new RecordingChain();
            RecordingResponse r = new RecordingResponse();
            filter("secret").doFilter(on(path), r.proxy(), c);
            assertFalse(path + " 不该让匿名请求通过", c.called);
            assertEquals(path + " 应返回 403", 403, r.status);
        }
    }

    @Test
    public void 管理面带contextPath部署时也不能漏() throws Exception {
        RecordingChain c = new RecordingChain();
        RecordingResponse r = new RecordingResponse();
        filter("secret").doFilter(request("", "/schedule/jobinfo/list", "/schedule",
                "/jobinfo/list", null, null), r.proxy(), c);

        assertFalse("context-path 不得让管理面绕过 token", c.called);
        assertEquals(403, r.status);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Access-Token", "secret");
        RecordingChain ok = new RecordingChain();
        filter("secret").doFilter(request("", "/schedule/jobinfo/list", "/schedule",
                "/jobinfo/list", null, headers), new RecordingResponse().proxy(), ok);
        assertTrue("带上正确 token 就该放行", ok.called);
    }

    @Test
    public void 只有登录口和静态外壳不需要token() throws Exception {
        for (String path : new String[]{"/user/login", "/", "/index.html", "/favicon.ico",
                "/assets/index.js", "/static/app.css", "/error"}) {
            RecordingChain c = new RecordingChain();
            RecordingResponse r = new RecordingResponse();
            filter("secret").doFilter(on(path), r.proxy(), c);
            assertTrue(path + " 必须在登录前就能访问", c.called);
            assertEquals(path + " 不该被拦", 0, r.status);
        }
        // 阳性对照：/user/list 必须是被拦的那一个，否则上面全绿只说明"谁都放行"
        RecordingChain control = new RecordingChain();
        filter("secret").doFilter(on("/user/list"), new RecordingResponse().proxy(), control);
        assertFalse("对照组：/user/list 不该在免鉴权名单里", control.called);
    }

    @Test
    public void 没配token时管理面放行但这是演示模式() throws Exception {
        // 行为不变（否则自带的演示 UI 直接不能用），但这件事必须在启动日志里说出来，
        // 见 TokenAuthFilter.init 里那条 warn。
        RecordingChain c = new RecordingChain();
        filter(null).doFilter(on("/jobinfo/list"), new RecordingResponse().proxy(), c);
        assertTrue(c.called);

        RecordingChain blank = new RecordingChain();
        filter("   ").doFilter(on("/jobinfo/list"), new RecordingResponse().proxy(), blank);
        assertTrue(blank.called);
    }

    /**
     * 过滤器内部判断改对了，注册范围漏了照样是零。
     * <p>
     * 只注册 {@code /executor/*} 时，{@code /jobinfo/list} 根本走不到 doFilter，
     * 上面那些 403 断言一条都跑不到——所以这一层要单独钉。
     */
    @Test
    public void 过滤器必须注册在整个应用入口上() {
        ScheduleProperties props = new ScheduleProperties();
        props.setAccessToken("secret");
        LoginSessionStore store = new LoginSessionStore();
        FilterRegistrationBean<TokenAuthFilter> registration =
                new ZScheduleAutoConfiguration().tokenAuthFilterRegistration(props, store);

        assertEquals("urlPattern 必须是 /*，否则管理面根本不经过鉴权",
                Collections.singletonList("/*"), new ArrayList<String>(registration.getUrlPatterns()));
        assertTrue("注册进去的必须是那个会自己判路径的过滤器",
                registration.getFilter() instanceof TokenAuthFilter);
        // 签发方与校验方各自一张表 = "登录成功但每个请求都 403"，装配时最容易写错的就是这一条
        assertSame("过滤器拿到的必须是那个正在发号的服务",
                store, fieldOf(registration.getFilter(), "sessionStore"));
    }

    @Test
    public void executor前缀不带子路径也要鉴权() throws Exception {
        filter("secret").doFilter(request("", "/executor", "", "/executor", null, null),
                response.proxy(), chain);
        assertFalse(chain.called);
        assertEquals(403, response.status);
    }

    // ---- token 校验 ----

    @Test
    public void 请求头token正确才放行() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Access-Token", "secret");
        filter("secret").doFilter(executorRequest(null, headers), response.proxy(), chain);

        assertTrue(chain.called);
        assertEquals(0, response.status);
    }

    @Test
    public void 参数token优先于请求头() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Access-Token", "wrong");
        filter("secret").doFilter(executorRequest("secret", headers), response.proxy(), chain);

        assertTrue("参数带对了就该放行(优先级高于请求头)", chain.called);
    }

    @Test
    public void token不符返回403且不进入业务() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Access-Token", "s3cret");
        filter("secret").doFilter(executorRequest(null, headers), response.proxy(), chain);

        assertFalse(chain.called);
        assertEquals(403, response.status);
        assertTrue("响应体要给出 JSON: " + response.body(), response.body().contains("403"));
    }

    @Test
    public void 配置了token却完全没带时拒绝() throws Exception {
        filter("secret").doFilter(executorRequest(null, null), response.proxy(), chain);

        assertFalse(chain.called);
        assertEquals(403, response.status);
    }

    @Test
    public void token配置为空白等于未开启鉴权() throws Exception {
        filter("   ").doFilter(executorRequest(null, null), response.proxy(), chain);
        assertTrue("未配置 token 时按文档放行", chain.called);

        filter(null).doFilter(executorRequest(null, null), response.proxy(), chain);
        assertTrue(chain.called);
    }

    @Test
    public void 只匹配前缀的token不能通过() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Access-Token", "sec");
        filter("secret").doFilter(executorRequest(null, headers), response.proxy(), chain);

        assertFalse(chain.called);
        assertEquals(403, response.status);
    }

    // ---- 会话令牌：登录换来的凭证，以及它带进来的那个"是谁" ----

    @Test
    public void 会话令牌本身就是凭证() throws Exception {
        LoginSessionStore store = new LoginSessionStore();
        String token = store.issue(user(1, "zoe", "NORMAL")).getToken();

        RecordingChain c = new RecordingChain();
        filter("secret", store).doFilter(bearer("/jobinfo/list", token), new RecordingResponse().proxy(), c);

        assertTrue("出示登录换来的令牌就该等于已登录", c.called);
        // 阳性对照：同一把令牌在另一张会话表里验不过，说明上面通过的是"这张表认识它"而不是"全都放行"
        RecordingChain other = new RecordingChain();
        filter("secret", new LoginSessionStore())
                .doFilter(bearer("/jobinfo/list", token), new RecordingResponse().proxy(), other);
        assertFalse("别的实例签的令牌不算数", other.called);
    }

    @Test
    public void 过期或伪造的令牌仍按未登录处理() throws Exception {
        LoginSessionStore shortLived = new LoginSessionStore(10, 30L);
        String stale = shortLived.issue(user(2, "amy", "ADMIN")).getToken();
        Thread.sleep(70L);

        RecordingChain expiredChain = new RecordingChain();
        RecordingResponse expiredResponse = new RecordingResponse();
        filter("secret", shortLived).doFilter(bearer("/jobinfo/list", stale), expiredResponse.proxy(), expiredChain);
        assertFalse("过期令牌不得混进门", expiredChain.called);
        assertEquals(403, expiredResponse.status);

        RecordingChain unknownChain = new RecordingChain();
        RecordingResponse unknownResponse = new RecordingResponse();
        filter("secret", new LoginSessionStore())
                .doFilter(bearer("/executor/callback", stale), unknownResponse.proxy(), unknownChain);
        assertFalse("另一张会话表不认识的令牌同样不算数", unknownChain.called);
        assertEquals(403, unknownResponse.status);
    }

    @Test
    public void 会话身份挂进请求而共享密钥不挂() throws Exception {
        LoginSessionStore store = new LoginSessionStore();
        String token = store.issue(user(21, "gina", "ADMIN")).getToken();

        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        RecordingChain c = new RecordingChain();
        filter("secret", store).doFilter(bearer("/dashboard/stats", token, attributes),
                new RecordingResponse().proxy(), c);
        assertTrue(c.called);

        LoginSession identity = (LoginSession) attributes.get(TokenAuthFilter.IDENTITY_ATTRIBUTE);
        assertNotNull("身份必须落在约定的属性上，否则下游拿不到“是谁”", identity);
        assertEquals(21, identity.getUserId());
        assertEquals("gina", identity.getUsername());
        assertTrue(identity.isAdmin());

        // 反方向：共享密钥进得来，但它不区分是谁——硬造一个身份出来只会骗过下游
        Map<String, Object> sharedKeyAttributes = new LinkedHashMap<String, Object>();
        RecordingChain byKey = new RecordingChain();
        filter("secret", store).doFilter(bearer("/dashboard/stats", "secret", sharedKeyAttributes),
                new RecordingResponse().proxy(), byKey);
        assertTrue("共享密钥本来就是凭证", byKey.called);
        assertNull("共享密钥不该带出会话身份",
                sharedKeyAttributes.get(TokenAuthFilter.IDENTITY_ATTRIBUTE));
        assertNull(TokenAuthFilter.currentIdentity(request("", "/user/list", "", "/user/list",
                null, null, sharedKeyAttributes)));
    }

    @Test
    public void 共享密钥那一支留下全权标记而会话与匿名不留() throws Exception {
        // 下游要分辨的不是"能不能进门"，而是"知不知道是谁"：铸管理员这类动作只有答得出
        // "是谁在铸"的凭证才允许（见 UserController.canMintAdmin），所以这个标记是那道闸的唯一依据。
        LoginSessionStore store = new LoginSessionStore();
        String adminToken = store.issue(user(51, "boss", "ADMIN")).getToken();

        Map<String, Object> byKey = new LinkedHashMap<String, Object>();
        HttpServletRequest secretRequest = bearer("/jobinfo/list", "secret", byKey);
        filter("secret", store).doFilter(secretRequest, new RecordingResponse().proxy(), new RecordingChain());
        assertTrue("共享密钥放行的那一次必须留下全权标记", TokenAuthFilter.presentedSharedSecret(secretRequest));

        Map<String, Object> bySession = new LinkedHashMap<String, Object>();
        HttpServletRequest sessionRequest = bearer("/jobinfo/list", adminToken, bySession);
        filter("secret", store).doFilter(sessionRequest, new RecordingResponse().proxy(), new RecordingChain());
        assertFalse("会话令牌不是共享密钥（它是 ADMIN 会话也一样，别把两种凭证混成一个标记）",
                TokenAuthFilter.presentedSharedSecret(sessionRequest));
        assertNotNull("但它必须带出身份", TokenAuthFilter.currentIdentity(sessionRequest));

        Map<String, Object> anonymous = new LinkedHashMap<String, Object>();
        HttpServletRequest demoRequest = request("/user/add", "/user/add", "", null, null, null, anonymous);
        filter(null, store).doFilter(demoRequest, new RecordingResponse().proxy(), new RecordingChain());
        assertFalse("演示模式的匿名请求两种凭证都不是 ⇒ 两个标记都拿不到",
                TokenAuthFilter.presentedSharedSecret(demoRequest));
        assertNull(TokenAuthFilter.currentIdentity(demoRequest));
    }

    @Test
    public void 普通会话改不了账号而管理员会话能() throws Exception {
        LoginSessionStore store = new LoginSessionStore();
        String normal = store.issue(user(31, "peon", "NORMAL")).getToken();
        String admin = store.issue(user(32, "boss", "ADMIN")).getToken();

        for (String path : new String[]{"/user/add", "/user/update", "/user/remove"}) {
            RecordingChain c = new RecordingChain();
            RecordingResponse r = new RecordingResponse();
            filter("secret", store).doFilter(bearer(path, normal), r.proxy(), c);
            assertFalse(path + " 不该让普通会话通过", c.called);
            assertEquals(path + " 应返回 403", 403, r.status);
            assertTrue(path + " 的拒绝理由要说清是角色不够: " + r.body(), r.body().contains("需要管理员角色"));

            RecordingChain ok = new RecordingChain();
            filter("secret", store).doFilter(bearer(path, admin), new RecordingResponse().proxy(), ok);
            assertTrue(path + " 管理员会话必须能过（否则上面那组只是“全拦”）", ok.called);
        }
        // 阳性对照：读侧不收口，普通会话照样能看列表
        RecordingChain read = new RecordingChain();
        filter("secret", store).doFilter(bearer("/user/list", normal), new RecordingResponse().proxy(), read);
        assertTrue(read.called);
    }

    @Test
    public void 共享密钥不受角色闸约束() throws Exception {
        // 它是“全权”这件事是本版本的设计：机器/执行器没有会话，也没有角色可言。
        // 这条断言钉的是"闸没有误伤唯一一种非会话凭证"，不是认可它继续敞开。
        RecordingChain c = new RecordingChain();
        filter("secret", new LoginSessionStore())
                .doFilter(bearer("/user/add", "secret"), new RecordingResponse().proxy(), c);

        assertTrue(c.called);
    }

    @Test
    public void 演示模式下匿名全开但出示的会话仍被限角色() throws Exception {
        LoginSessionStore store = new LoginSessionStore();
        String normal = store.issue(user(41, "peon", "NORMAL")).getToken();

        RecordingChain anonymous = new RecordingChain();
        filter(null, store).doFilter(on("/user/add"), new RecordingResponse().proxy(), anonymous);
        assertTrue("未配置 token 时管理面对匿名敞开（启动日志里那条 warn 就是这件事）", anonymous.called);

        RecordingChain identified = new RecordingChain();
        filter(null, store).doFilter(bearer("/user/add", normal), new RecordingResponse().proxy(), identified);
        assertFalse("敞开的门不该让一个自报的普通会话顺手拿到改账号的权力", identified.called);
    }

    // ---- 替身 ----

    private static TokenAuthFilter filter(String accessToken) {
        return filter(accessToken, null);
    }

    private static TokenAuthFilter filter(String accessToken, LoginSessionStore store) {
        ScheduleProperties properties = new ScheduleProperties();
        properties.setAccessToken(accessToken);
        return new TokenAuthFilter(properties, store);
    }

    /** 一条带着会话/密钥令牌的具体路径请求（令牌走请求头，与参数路径共用同一段判断）。 */
    private static HttpServletRequest bearer(String path, String token) {
        return bearer(path, token, new LinkedHashMap<String, Object>());
    }

    private static HttpServletRequest bearer(String path, String token, Map<String, Object> attributes) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Access-Token", token);
        return request(path, path, "", null, null, headers, attributes);
    }

    private static UserDO user(int id, String username, String role) {
        UserDO d = new UserDO();
        d.setId(id);
        d.setUsername(username);
        d.setRole(role);
        return d;
    }

    private static Object fieldOf(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("找不到字段 " + fieldName + "，装配形状变了：" + e, e);
        }
    }

    /**
     * 一次打在具体路径上的普通请求。
     * <p>
     * servletPath 必须单独给出、pathInfo 传 null：过滤器注册在 /* 时，Spring MVC 把
     * {@code /jobinfo/list} 整段放进 servletPath、pathInfo 为 null。两头都填会把路径拼成
     * {@code /jobinfo/list/jobinfo/list}，于是"公开路径必须放行"那组断言会因为拼出来的
     * 字符串不在名单里而恰好通过，测不出真实形状。
     */
    private static HttpServletRequest on(String path) {
        return request(path, path, "", null, null, null);
    }

    private static HttpServletRequest executorRequest(String accessTokenParam, Map<String, String> headers) {
        return request("", "/executor/callback", "", "/executor/callback", accessTokenParam, headers);
    }

    private static HttpServletRequest request(final String servletPath, final String requestURI,
                                              final String contextPath, final String pathInfo,
                                              final String accessToken, final Map<String, String> headers) {
        return request(servletPath, requestURI, contextPath, pathInfo, accessToken, headers,
                new LinkedHashMap<String, Object>());
    }

    private static HttpServletRequest request(final String servletPath, final String requestURI,
                                              final String contextPath, final String pathInfo,
                                              final String accessToken, final Map<String, String> headers,
                                              final Map<String, Object> attributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                TokenAuthFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("getServletPath".equals(name)) {
                            return servletPath;
                        }
                        if ("getPathInfo".equals(name)) {
                            return pathInfo;
                        }
                        if ("getRequestURI".equals(name)) {
                            return requestURI;
                        }
                        if ("getContextPath".equals(name)) {
                            return contextPath;
                        }
                        if ("getParameter".equals(name)) {
                            return "accessToken".equals(args[0]) ? accessToken : null;
                        }
                        if ("getHeader".equals(name)) {
                            return headers == null ? null : headers.get((String) args[0]);
                        }
                        if ("setAttribute".equals(name)) {
                            attributes.put((String) args[0], args[1]);
                            return null;
                        }
                        if ("getAttribute".equals(name)) {
                            return attributes.get((String) args[0]);
                        }
                        if ("getRemoteAddr".equals(name)) {
                            return "127.0.0.1";
                        }
                        if (method.getReturnType() == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (method.getReturnType() == int.class) {
                            return Integer.valueOf(0);
                        }
                        return null;
                    }
                });
    }

    private static class RecordingChain implements FilterChain {
        boolean called;

        public void doFilter(javax.servlet.ServletRequest request, javax.servlet.ServletResponse response) {
            called = true;
        }
    }

    private static class RecordingResponse {
        int status;
        private final StringWriter body = new StringWriter();
        private final PrintWriter writer = new PrintWriter(body);

        HttpServletResponse proxy() {
            return (HttpServletResponse) Proxy.newProxyInstance(
                    TokenAuthFilterTest.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("setStatus".equals(name)) {
                                status = (Integer) args[0];
                            }
                            if ("getWriter".equals(name)) {
                                return writer;
                            }
                            if (method.getReturnType() == boolean.class) {
                                return Boolean.FALSE;
                            }
                            if (method.getReturnType() == int.class) {
                                return Integer.valueOf(0);
                            }
                            return null;
                        }
                    });
        }

        String body() {
            writer.flush();
            return body.toString();
        }
    }
}
