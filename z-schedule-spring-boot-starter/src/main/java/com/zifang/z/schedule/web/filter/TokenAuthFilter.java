package com.zifang.z.schedule.web.filter;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.web.auth.LoginSession;
import com.zifang.z.schedule.web.auth.LoginSessionStore;
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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Token 认证过滤器。
 * <p>
 * 默认对<b>全部</b>入口校验 accessToken，只有登录口与静态外壳放行；未配置 token 时跳过验证
 * （演示模式，但会在启动时把"管理面全开"这件事打出来，不再静默）。
 * <p>
 * 认可的凭证有两种：
 * <ul>
 *   <li>{@code z.schedule.accessToken} 那个共享密钥 —— 配了它的人拿到的就是全权（机器/执行器用它，
 *       它没有"是谁"的概念，也就无从分权）</li>
 *   <li>{@code /user/login} 换来的会话令牌 —— 解析回具体的用户与角色，作为
 *       {@link #IDENTITY_ATTRIBUTE} 挂到请求上，并按 {@link #ADMIN_ONLY_PATHS} 收窄可及范围</li>
 * </ul>
 * <p>
 * token 来源优先级：请求参数 {@code accessToken} &gt; 请求头 {@code X-Access-Token}。
 */
public class TokenAuthFilter implements Filter {

    private static final Logger log = LogManager.getLogger(TokenAuthFilter.class);

    private static final String PARAM_ACCESS_TOKEN = "accessToken";
    private static final String HEADER_ACCESS_TOKEN = "X-Access-Token";

    /** 会话身份挂在请求上的属性名；下游（控制器、按角色放行的判断）从这里取"是谁"。 */
    public static final String IDENTITY_ATTRIBUTE = "z.schedule.loginSession";

    /**
     * "这一次是靠共享密钥进来的"标记的属性名。
     * <p>
     * 两种凭证的差别不在"能不能进门"，而在**知不知道是谁**：共享密钥不区分人，会话令牌区分。
     * 所以要判断"能不能做只有管理员能做的事"，只有这个属性 + {@link #IDENTITY_ATTRIBUTE}
     * 合起来才答得出——缺了前者，管理员自己带的会话和无主的名令在同一段代码里长得一样。
     */
    public static final String FULL_AUTHORITY_ATTRIBUTE = "z.schedule.fullAuthority";

    /**
     * 不需要 token 就能到的路径。
     * <p>
     * 只放"登录前就必须能用"的东西：登录口本身、静态外壳（SPA 的 html/js 不含任何数据）、
     * 容器的 error 转发（否则一次 404 会被包装成 403，排查时看不出真实失败点）。
     * 其余一律按需要 token 处理——包括 {@code /actuator/*}，它的 health 在这个应用里开了
     * show-details=always，会把数据源信息吐给匿名访问者。
     * <p>
     * {@code /user/login} 与 {@code /api/schedule/user/login} 都要在：{@code UserController} 挂了两条路径，
     * 只放裸路径的话，别名那条的登录口反而要 token —— 登录前拿不到 token ⇒ 登录流程自锁。
     */
    private static final Set<String> PUBLIC_PATHS = new HashSet<String>(Arrays.asList(
            "/", "/index.html", "/favicon.ico", "/error",
            "/user/login", "/api/schedule/user/login"));

    /** 静态资源前缀（把打好的前端放进 {@code static/} 时不至于连壳都下不下来）。 */
    private static final String[] PUBLIC_PREFIXES = {"/assets/", "/static/", "/public/"};

    /**
     * 管理面<b>根级裸路径</b>的前缀（{@code /jobinfo}、{@code /jobgroup}、{@code /joblog}、
     * {@code /glue}、{@code /user}）。
     *
     * <p>判定用「前缀 + {@code /}」而不是 {@code startsWith(prefix)}：{@code /jobinfos}
     * 不是 {@code /jobinfo}，而 {@code startsWith("/jobinfo")} 会把它算进去。
     *
     * <p><b>为什么它们要被单独拎出来</b>：这五个前缀挂在根级，宿主（z-opc 那种聚合进程）的登录闸门
     * 按路径前缀匹配（api 那一段的路径），根级路径一条都不落在射程内 ⇒ 匿名直达业务层。
     * 而 controller 的类注释写着「鉴权: 由调度管理端统一拦截」——那句前提只在独立部署的
     * z-schedule-admin 里成立，嵌进宿主就没有承接方了。所以这道闸门由 starter 自带。
     *
     * <p><b>为什么不在这里一并挡掉 {@code /executor/**}</b>：那是执行器进程 → 调度中心的 M2M 回调，
     * 没有用户会话可言（执行器拿不到也不需要用户令牌），挡住它等于打断执行器注册链路。
     * {@code /dashboard*} 同理：根级页面路径，与卡内「显式排除 /dashboard*」的口径一致。
     */
    private static final String[] BARE_MANAGEMENT_PREFIXES = {
            "/jobinfo/", "/jobgroup/", "/joblog/", "/glue/", "/user/"
    };

    /**
     * 裸路径闸门要放行的例外：{@code /user/login} 是登录口本身，登录前必须能调
     * （与 {@link #PUBLIC_PATHS} 的理由同源）。
     */
    private static final Set<String> BARE_PATH_EXEMPT = new HashSet<String>(Arrays.asList(
            "/user/login"));

    /**
     * 只有管理员会话能碰的路径。
     * <p>
     * 这一组是 {@code role} 列的读者：建/改/删账号决定了"谁能拿到 ADMIN"，
     * 交给普通会话就等于谁都能给自己提权。
     * <p>
     * 只约束<b>会话</b>身份的两种例外，都是现状而不是漏：共享密钥 {@code accessToken} 本来就不区分
     * "是谁"（它就是全权）；未配置 token 的演示模式下整个管理面是敞开的，这里单独拦一个口没有意义。
     * 读侧（{@code /user/list}）暂不按角色收口——它吐出的是用户名与角色，不含口令散列（见
     * {@code DoMapper.toDTO}），而"按 jobGroup 限权"是 {@code permission} 列的另一件事。
     * <p>
     * <b>两条路径都要列</b>：{@code UserController} 同时挂 {@code /user} 与 {@code /api/schedule/user}，
     * 只列裸路径的话，别名那条就绕过了角色闸（普通会话就能建/改/删账号 ⇒ 给自己提权）。
     */
    private static final Set<String> ADMIN_ONLY_PATHS = new HashSet<String>(Arrays.asList(
            "/user/add", "/user/update", "/user/remove",
            "/api/schedule/user/add", "/api/schedule/user/update", "/api/schedule/user/remove"));

    private final ScheduleProperties scheduleProperties;

    /** 会话令牌 → 身份。为 {@code null} 时本过滤器只认共享密钥（令牌一律走 403 分支）。 */
    private final LoginSessionStore sessionStore;

    public TokenAuthFilter(ScheduleProperties scheduleProperties) {
        this(scheduleProperties, null);
    }

    public TokenAuthFilter(ScheduleProperties scheduleProperties, LoginSessionStore sessionStore) {
        this.scheduleProperties = scheduleProperties;
        this.sessionStore = sessionStore;
    }

    public ScheduleProperties getScheduleProperties() {
        return scheduleProperties;
    }

    /**
     * 这次请求是谁。
     *
     * @return 出示了有效会话令牌时给出身份；共享密钥进来的、演示模式匿名进来的都回 {@code null}
     */
    public static LoginSession currentIdentity(HttpServletRequest request) {
        Object attribute = request.getAttribute(IDENTITY_ATTRIBUTE);
        return attribute instanceof LoginSession ? (LoginSession) attribute : null;
    }

    /**
     * 这次请求是不是共享密钥放进来的（{@link #IDENTITY_ATTRIBUTE} 的反面：全权，但没有人）。
     *
     * @return 只有过滤器在校验通过的共享密钥那一支上才会置真；会话令牌与演示模式匿名都是假
     */
    public static boolean presentedSharedSecret(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(FULL_AUTHORITY_ATTRIBUTE));
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        boolean barePathsClosed = !scheduleProperties.isExposeBarePaths();
        if (barePathsClosed) {
            log.info("TokenAuthFilter 初始化, urlPattern=/*（管理面裸路径 /jobinfo /jobgroup /joblog /glue /user "
                    + "只认凭证，匿名请求被挡在业务层之前；正式入口是 /api/schedule/**，"
                    + "/executor/** 与 /dashboard* 不受影响）");
        }
        String token = scheduleProperties.getAccessToken();
        if (token == null || token.trim().isEmpty()) {
            if (barePathsClosed) {
                log.warn("[z-schedule] 未配置 z.schedule.accessToken：裸路径管理面已关闭（匿名进不去），"
                        + "但 /api/schedule/** 与 /dashboard、/actuator 对任何能连到本端口的人完全敞开，"
                        + "包括改任务、删任务、伪造执行回报。生产部署必须配这个值。");
            } else {
                log.warn("[z-schedule] 未配置 z.schedule.accessToken 且 z.schedule.expose-bare-paths=true："
                        + "管理面（/jobinfo、/joblog、/jobgroup、/glue、/user、/dashboard、/actuator）"
                        + "对任何能连到本端口的人完全敞开，包括改任务、删任务、伪造执行回报。"
                        + "生产部署必须配这个值。");
            }
        } else {
            log.info("TokenAuthFilter 初始化, urlPattern=/*（除 {} 外全部校验 accessToken）", PUBLIC_PATHS);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = mappedPath(httpRequest);
        if (isPublic(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // 出示的凭证：参数优先于请求头（与历史行为一致）
        String presented = httpRequest.getParameter(PARAM_ACCESS_TOKEN);
        if (presented == null || presented.trim().isEmpty()) {
            presented = httpRequest.getHeader(HEADER_ACCESS_TOKEN);
        }

        // 先认会话令牌：它带着"是谁"，是两种凭证里唯一能被分权使用的那一种
        LoginSession identity = sessionStore == null ? null : sessionStore.resolve(presented);
        if (identity != null) {
            if (ADMIN_ONLY_PATHS.contains(uri) && !identity.isAdmin()) {
                log.warn("非管理员会话尝试改账号被拒, uri={}, username={}, role={}, remoteAddr={}",
                        uri, identity.getUsername(), identity.getRole(), httpRequest.getRemoteAddr());
                sendForbidden(httpResponse, "需要管理员角色");
                return;
            }
            httpRequest.setAttribute(IDENTITY_ATTRIBUTE, identity);
            chain.doFilter(request, response);
            return;
        }

        String configuredToken = scheduleProperties.getAccessToken();

        // 未配置 token → 跳过验证（演示模式）
        if (configuredToken == null || configuredToken.trim().isEmpty()) {
            // 但裸路径仍要拦：这一支若直接放行，管理面就对匿名全开——而管理面挂在根级时，
            // 宿主的登录闸门按路径前缀匹配根本管不到它们（z-opc 那种聚合进程只挡 api 那一段），
            // 匿名直达业务层，含写端点。
            if (barePathsClosed(uri)) {
                rejectBarePath(httpRequest, httpResponse, uri);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        // 再认共享密钥
        if (tokenMatches(configuredToken, presented)) {
            // 记一笔"这次是全权凭证放行的"：会话令牌带身份，共享密钥不带——
            // 下游要区分"管理员会话在做管理员的事"和"握着口令的任何人"，只能靠这个属性。
            httpRequest.setAttribute(FULL_AUTHORITY_ATTRIBUTE, Boolean.TRUE);
            chain.doFilter(request, response);
        } else {
            log.warn("accessToken 不合法, uri={}, remoteAddr={}", uri, httpRequest.getRemoteAddr());
            sendForbidden(httpResponse, " accessToken 不合法");
        }
    }

    /**
     * 裸路径闸门是否该拦下这一次请求。
     *
     * <p>拦的是<b>没有出示任何有效凭证</b>的请求。会话令牌与共享密钥两条分支都在它之前就放行了，
     * 走到这里的只剩匿名请求。
     */
    private boolean barePathsClosed(String uri) {
        return !scheduleProperties.isExposeBarePaths() && isBareManagementPath(uri);
    }

    /**
     * 裸路径闸门的拒绝响应：拦在业务层之前，不让请求进 controller。
     *
     * <p>回 <b>401</b> 而不是 403：这次请求缺的是凭证，不是"凭证不够"——
     * 匿名请求既没有会话令牌也没有共享密钥。宿主（z-opc 那种聚合进程）对没有登录态的
     * 请求也是回 401 JSON，两边口径一致，调用方按同一个分支处理。
     */
    private void rejectBarePath(HttpServletRequest request, HttpServletResponse response, String uri)
            throws IOException {
        log.warn("管理面裸路径未开放, uri={}, remoteAddr={}", uri, request.getRemoteAddr());
        sendJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                "管理面裸路径未开放，请使用 /api/schedule/** 路径");
    }

    @Override
    public void destroy() {
        log.info("TokenAuthFilter 销毁");
    }

    /**
     * 容器完成映射后的路径。
     * <p>
     * 不能用 {@link HttpServletRequest#getRequestURI()}:它带着 context-path 且不解码,
     * admin 以 {@code context-path=/schedule} 部署时 URI 是 {@code /schedule/executor/callback},
     * 按前缀判断会让整个执行器回调接口绕过鉴权;{@code %65} 之类的编码同样能绕过。
     * servletPath + pathInfo 由容器解码并去掉 context-path 后给出,才是可比对的路径。
     */
    static String mappedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        if (!path.isEmpty()) {
            return path;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    /** 是否属于"登录前就必须能用"的公开路径。 */
    private static boolean isPublic(String path) {
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否属于管理面的根级裸路径。
     *
     * <p>判定用「前缀 + {@code /}」而不是 {@code startsWith(prefix)}：{@code /jobinfos}
     * 不是 {@code /jobinfo}，而 {@code startsWith("/jobinfo")} 会把它算进去。
     *
     * <p>前缀表里写的是带尾斜杠的形态（{@code "/jobinfo/"}），所以<b>目录本身</b>
     * （{@code /jobinfo}）不算裸路径——它没有子端点，落不到任何 controller 上。
     */
    private static boolean isBareManagementPath(String path) {
        if (BARE_PATH_EXEMPT.contains(path)) {
            return false;
        }
        for (String prefix : BARE_MANAGEMENT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 定长时间比较,避免按字节探测 token。 */
    private static boolean tokenMatches(String configured, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回 403 JSON 响应。
     * <p>
     * 区分"凭证不对"和"凭证对但角色不够"：前者让人去翻口令，后者让人去申请提权。
     */
    private void sendForbidden(HttpServletResponse response, String reason) throws IOException {
        sendJson(response, HttpServletResponse.SC_FORBIDDEN, reason);
    }

    /** 返回指定状态码的 JSON 响应（401/403 共用这一支）。 */
    private void sendJson(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":" + status + ",\"msg\":\"" + reason + "\"}";
        PrintWriter writer = response.getWriter();
        writer.write(body);
        writer.flush();
    }
}
