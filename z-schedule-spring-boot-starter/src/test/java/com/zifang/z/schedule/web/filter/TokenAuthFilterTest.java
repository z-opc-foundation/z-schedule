package com.zifang.z.schedule.web.filter;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.web.config.ZScheduleAutoConfiguration;
import org.junit.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        FilterRegistrationBean<TokenAuthFilter> registration =
                new ZScheduleAutoConfiguration().tokenAuthFilterRegistration(props);

        assertEquals("urlPattern 必须是 /*，否则管理面根本不经过鉴权",
                Collections.singletonList("/*"), new ArrayList<String>(registration.getUrlPatterns()));
        assertTrue("注册进去的必须是那个会自己判路径的过滤器",
                registration.getFilter() instanceof TokenAuthFilter);
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

    // ---- 替身 ----

    private static TokenAuthFilter filter(String accessToken) {
        ScheduleProperties properties = new ScheduleProperties();
        properties.setAccessToken(accessToken);
        return new TokenAuthFilter(properties);
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
