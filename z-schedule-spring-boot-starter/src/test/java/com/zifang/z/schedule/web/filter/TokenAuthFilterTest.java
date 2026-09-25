package com.zifang.z.schedule.web.filter;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import org.junit.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link TokenAuthFilter} 的鉴权边界。
 * <p>
 * 这个过滤器守的是执行器回调接口(能触发/回报任务执行)，所以最值得钉的是
 * "什么形状的请求会走到校验"。旧实现拿 {@code getRequestURI()} 前缀判断，
 * 而 {@code getRequestURI()} 带 context-path 也不解码——于是
 * {@code context-path=/schedule} 部署下的 {@code /schedule/executor/callback}
 * 会整个跳过 token 校验。
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
    public void 非执行器路径直接放行() throws Exception {
        filter("secret").doFilter(request("/jobinfo/list", null, "/jobinfo/list", null, null, null),
                response.proxy(), chain);

        assertTrue("非 /executor/* 不该被拦", chain.called);
        assertEquals(0, response.status);
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
