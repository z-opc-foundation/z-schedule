package io.github.yuku123.z.schedule.web.filter;

import io.github.yuku123.z.schedule.core.config.ScheduleProperties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Token 认证过滤器。
 * <p>
 * 拦截 {@code /executor/*} 路径，校验请求中的 accessToken 是否与配置一致。
 * 未配置 token 时跳过验证（允许无 token 访问）。
 * <p>
 * token 来源优先级：请求参数 {@code accessToken} &gt; 请求头 {@code X-Access-Token}。
 */
public class TokenAuthFilter implements Filter {

    private static final Logger log = LogManager.getLogger(TokenAuthFilter.class);

    private static final String URL_PATTERN = "/executor/*";
    private static final String PARAM_ACCESS_TOKEN = "accessToken";
    private static final String HEADER_ACCESS_TOKEN = "X-Access-Token";

    private final ScheduleProperties scheduleProperties;

    public TokenAuthFilter(ScheduleProperties scheduleProperties) {
        this.scheduleProperties = scheduleProperties;
    }

    public ScheduleProperties getScheduleProperties() {
        return scheduleProperties;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("TokenAuthFilter 初始化, urlPattern={}", URL_PATTERN);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();
        if (!matchExecutorUri(uri)) {
            chain.doFilter(request, response);
            return;
        }

        String configuredToken = scheduleProperties.getAccessToken();

        // 未配置 token → 跳过验证
        if (configuredToken == null || configuredToken.trim().isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        // 从请求参数或请求头中获取 token
        String requestToken = httpRequest.getParameter(PARAM_ACCESS_TOKEN);
        if (requestToken == null || requestToken.trim().isEmpty()) {
            requestToken = httpRequest.getHeader(HEADER_ACCESS_TOKEN);
        }

        // 校验 token
        if (configuredToken.equals(requestToken)) {
            chain.doFilter(request, response);
        } else {
            log.warn("accessToken 不合法, uri={}, remoteAddr={}", uri, httpRequest.getRemoteAddr());
            sendForbidden(httpResponse);
        }
    }

    @Override
    public void destroy() {
        log.info("TokenAuthFilter 销毁");
    }

    /**
     * 判断 URI 是否匹配 /executor/* 路径。
     */
    private boolean matchExecutorUri(String uri) {
        return uri.startsWith("/executor/");
    }

    /**
     * 返回 403 JSON 响应。
     */
    private void sendForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":403,\"msg\":\" accessToken 不合法\"}";
        PrintWriter writer = response.getWriter();
        writer.write(body);
        writer.flush();
    }
}
