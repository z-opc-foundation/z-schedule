package com.zifang.z.schedule.web.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zifang.z.boot.datasource.starter.ModuleDataSourceTemplate;
import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.web.auth.LoginSessionStore;
import com.zifang.z.schedule.web.filter.TokenAuthFilter;
import com.zifang.z.schedule.web.service.AlarmService;
import com.zifang.z.schedule.web.service.impl.DefaultAlarmService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * z-schedule-web AutoConfiguration (供 main-starter embed).
 *
 * <p>历史: z-schedule-admin 是独立 Spring Boot app, 含 controllers + services + job handler.
 * admin 的 application.yml 含 server.port=8080 + context-path=/schedule, 作为 main-starter 依赖
 * 会污染 Spring 环境.
 *
 * <p>解决: 把 controllers + services + AutoConfig 拆到 z-schedule-web, 源码全部归位 web 包.
 * z-schedule-admin 留作独立启动器, 只包含 ZScheduleAdminApplication + 自己需要的子集.
 * (注: admin 模块已从父 pom.modules 移除, 这里不再扫描 admin 包, 避免扫描空包浪费时间。)
 *
 * <p><b>FEATURE: 持久化 + 集群协调</b>
 * <ul>
 *   <li>DataSource via {@link ModuleDataSourceTemplate}，配置前缀 {@code z.base.db.schedule.*}</li>
 *   <li>{@code @MapperScan} 注册 5 个 MyBatis Mapper（JobInfo/Log/Group/Registry/Leader）</li>
 *   <li>依赖 {@code z-boot-datasource-starter} + Druid 数据源</li>
 * </ul>
 *
 * <p><b>Dev profile 支持</b>：宿主可以只提供替身、不必再关本模块。{@code dataSourceSchedule} 与
 * {@code sqlSessionFactorySchedule} 各带一支 {@code @ConditionalOnMissingBean(name = …)}，
 * 按 <b>bean 名</b>退让（admin 的 {@code DevDataSourceConfig} 就是靠这两支同名 @Bean 换 H2）。
 * 仍保留 {@code z.base.db.schedule.disabled=true}：它是"宿主只补齐两支同名 bean 里的一支"时唯一
 * 能让 starter 整段不注册的路，也是已发布 1.0.4 那条路（见 {@code _doc/003_script/e2e/README.md} §19）。
 *
 * <p><b>为什么自带 {@code @EnableScheduling}</b>：Leader 续约（{@code LeaderElector.elect}）与
 * 周期 reconcile（{@code JobTriggerServiceImpl.reloadRunningJobs}）都挂在 {@code @Scheduled} 上，
 * 宿主没开调度时引擎会静默地永远不当选 Leader、也就永远不调度。注意 Spring 默认调度线程只有 1 条，
 * 续约与 reconcile 会互相排队（Leader 续约窗口 30s），嵌入方建议设
 * {@code spring.task.scheduling.pool.size >= 2}。
 */
@Configuration
@ComponentScan(basePackages = "com.zifang.z.schedule.web")
@Import(ZScheduleAutoConfiguration.AlarmServiceConfiguration.class)
@EnableScheduling
@MapperScan(
        basePackages = "com.zifang.z.schedule.web.domain.mapper",
        sqlSessionFactoryRef = "sqlSessionFactorySchedule"
)
public class ZScheduleAutoConfiguration extends ModuleDataSourceTemplate {

    /**
     * 调度系统配置属性（对应 z.schedule.* 前缀）。
     */
    @Bean
    @ConfigurationProperties(prefix = "z.schedule")
    public ScheduleProperties scheduleProperties() {
        return new ScheduleProperties();
    }

    /**
     * {@link AlarmService} 的扩展点。
     *
     * <p>放在这里而不是给 {@code DefaultAlarmService} 挂 {@code @Service}：
     * {@code @ComponentScan} 注册的 bean 不受条件注解约束，宿主一旦自己注册实现，
     * {@code JobTriggerServiceImpl} 的 {@code @Resource AlarmService} 就面对两个同类型候选，
     * 装配期直接启动失败——即"想换告警通道 = 必须先改本仓源码"。
     *
     * <p>bean 名刻意叫 {@code alarmService}（= 注入点的字段名）：
     * {@code @Resource} 先按名匹配，所以即使在 {@code @Import} 路径下条件判定早于宿主 bean 注册、
     * 兜底实现没能退让，注入也仍能确定地落在一个 bean 上，而不是抛 NoUniqueBeanDefinitionException。
     */
    @Configuration(proxyBeanMethods = false)
    public static class AlarmServiceConfiguration {

        @Bean
        @ConditionalOnMissingBean(AlarmService.class)
        public AlarmService alarmService() {
            return new DefaultAlarmService();
        }
    }

    /**
     * 登录会话表（{@code /user/login} 换来的令牌 → 用户身份）。
     * <p>
     * 与 {@link AlarmServiceConfiguration} 同一个理由：直挂 {@code @Component} 的话，
     * 宿主想换成共享存储（多实例部署下 A 机签发的令牌在 B 机验不过）就会撞成两个同类型候选，
     * 装配期直接启动失败。方法名 {@code sessionStore} = 各注入点的字段名，按名命中。
     */
    @Bean
    @ConditionalOnMissingBean(LoginSessionStore.class)
    public LoginSessionStore sessionStore() {
        return new LoginSessionStore();
    }

    /**
     * Token 认证过滤器注册。
     * <p>
     * 挂在 {@code /*} 上，由 {@link TokenAuthFilter} 自己按路径决定要不要校验：
     * 只注册 {@code /executor/*} 的话，管理面（建任务/删任务/看日志/改用户/dashboard/actuator）
     * 根本不会经过这个过滤器，配了 accessToken 也等于没配——过滤器里的路径判断会永远"通过"，
     * 测试再怎么断言 403 也照样绿。
     *
     * <p>{@code sessionStore} 必须和签发方是同一个实例：两张表就是"登录成功但每个请求都 403"。
     */
    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilterRegistration(ScheduleProperties props,
                                                                              LoginSessionStore sessionStore) {
        FilterRegistrationBean<TokenAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TokenAuthFilter(props, sessionStore));
        registration.addUrlPatterns("/*");
        registration.setName("tokenAuthFilter");
        registration.setOrder(1);
        return registration;
    }

    /**
     * 生产 / 默认环境：starter 自建 Druid + MySQL DataSource。
     * 用 {@code z.base.db.schedule.disabled=false}（或不设）启用。
     *
     * <p>{@code @ConditionalOnMissingBean(name = ...)} 是按 <b>bean 名</b> 退让，不是按类型：
     * 宿主往往自己就有主 {@code DataSource}，按类型退让会让本模块永远建不出调度库。
     */
    @Bean(name = "dataSourceSchedule")
    @ConditionalOnProperty(name = "z.base.db.schedule.disabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "dataSourceSchedule")
    public DataSource dataSourceSchedule(Environment env) {
        applySchedulePoolDefaults(env);
        DataSource ds = buildDataSource(env, "schedule");
        applyDriverConnectTimeouts(ds, env);
        return ds;
    }

    /**
     * 物理连接的默认时间界。<b>两个都给</b>，但一个的量在 5 s、一个的量在 60 s，理由见
     * {@link #applyDriverConnectTimeouts}。
     */
    static final int DEFAULT_SCHEDULE_CONNECT_TIMEOUT_MILLIS = 5000;

    /**
     * 读超时默认 60 s —— 不是"关"。依据与代价都写在该方法的注释里；设 0 = 显式退回"读无限等"。
     */
    static final int DEFAULT_SCHEDULE_SOCKET_TIMEOUT_MILLIS = 60000;

    /**
     * 给调度池的 JDBC 驱动补上 {@code connectTimeout} / {@code socketTimeout}。
     *
     * <p><b>两种"连不上库"的形状，界在不同的地方</b>（这一句是 2026-09-27 被自己的测例纠正过来的，
     * 原先这里写的是错的机制）：
     * <ul>
     *   <li><b>池空、要新建物理连接</b>：Druid 的调用线程等 {@code maxWait}，到点抛
     *       {@code GetConnectionTimeoutException}。实测
     *       （{@code ZSchedulePoolConnectTimeoutTest#建连那一段的界是maxWait而不是驱动超时}）：
     *       {@code max-wait=1000} + 黑洞端口 ⇒ 1015 ms 抛，抬到 3000 ⇒ 3006 ms 抛。
     *       这一形状<b>不需要本节的旋钮</b>来定总界；本节的旋钮管的是
     *       <b>每一次物理连接尝试</b>（后台创建线程上，失败后还会再试），
     *       测它有没有接线看 {@code #读超时只管单次尝试而整段的界仍是maxWait}。</li>
     *   <li><b>借到一条对端已经不回包的老连接</b>：{@code test-on-borrow} 是关的（刻意的，
     *       见 {@code ModuleDataSourceTemplate} 那段保活注释），借出<em>瞬间成功</em>，
     *       没有任何 {@code maxWait} 在前面 —— 查询的第一个读包就是无界的。
     *       线上量到的正是这一档：{@code /jobinfo/list} 连续 30 s、75 s、90 s 三次都无应答，
     *       而两个池的 {@code max-wait} 都是 60 s（读数与复跑见
     *       {@code _doc/003_script/e2e/README.md} §23）。<b>{@code maxWait} 解释不了它，
     *       {@code socketTimeout} 能。</b></li>
     * </ul>
     *
     * <p><b>为什么默认 60 s 而不是"开着怕砍查询"</b>：250 真机基线里最慢的合法落库语句是
     * 十几到几十毫秒量级（见 §4.2 那组 17–50 ms），60 s 是三个数量级的余量；而"不设"的代价就是
     * 上面那一档：一个请求线程 + 一条连接永久悬住，来一个悬一对，最后整张管理面跟着死。
     * 嫌它长的部署按 {@code z.base.db.schedule.socket-timeout-millis} 调，设 0 退回旧行为。
     *
     * <p>⚠ 诚实记账：<b>第二种形状本机没能复现</b> —— 它要一个真会讲 MySQL 协议、又能"中途变哑"的
     * 服务端，测例里造不出来（测例只量到了第一种形状 + {@code max-wait=-1} 时无限等这一对）。
     * 所以 60 s 这个数是<b>按已知最慢合法语句留余量定的，不是量出来的</b>。
     *
     * <p>两个键都走和池参数<b>同一套</b>优先级链：模块键 {@code z.base.db.schedule.*} &gt;
     * 全局兜底 {@code z.base.db.default.*} &gt; 本模块默认值。非 Druid 的替身原样放过。
     */
    void applyDriverConnectTimeouts(DataSource ds, Environment env) {
        if (!(ds instanceof DruidDataSource)) {
            return;
        }
        Binder binder = Binder.get(env);
        int connectMillis = getOrDefault(binder,
                "z.base.db.schedule.connect-timeout-millis",
                "z.base.db.default.connect-timeout-millis",
                DEFAULT_SCHEDULE_CONNECT_TIMEOUT_MILLIS);
        int socketMillis = getOrDefault(binder,
                "z.base.db.schedule.socket-timeout-millis",
                "z.base.db.default.socket-timeout-millis",
                DEFAULT_SCHEDULE_SOCKET_TIMEOUT_MILLIS);

        DruidDataSource dd = (DruidDataSource) ds;
        Properties merged = new Properties();
        if (dd.getConnectProperties() != null) {
            merged.putAll(dd.getConnectProperties());
        }
        if (connectMillis > 0) {
            merged.setProperty("connectTimeout", String.valueOf(connectMillis));
        }
        if (socketMillis > 0) {
            merged.setProperty("socketTimeout", String.valueOf(socketMillis));
        }
        dd.setConnectProperties(merged);
    }

    /**
     * 调度池默认并发。{@code ModuleDataSourceTemplate} 里那个 20 是**所有模块共用**的兜底值，
     * 不该由 z-schedule 去改它（那会顺带抬高 z-lc / z-kb 等每个宿主的池），所以默认值由本模块
     * 以一个**最低优先级**的属性源给出：宿主的 yml / 环境变量 / {@code --z.base.db.schedule.max-active=…}
     * 全部压在它上面。
     *
     * <p>取 40 的依据（250 真机，N=1600 个 1 Hz 任务、30 s 窗口，durability 全程未降级）：
     * max-active 20 ⇒ 346 次/s，40 ⇒ 545，80 ⇒ 905，且忙连接峰值每次都正好等于上限。
     * 抬池只涨 1.66–1.72 倍（不是 2 倍），而 MySQL 侧 {@code max_connections} 是被同库其他服务
     * 分摊的 ⇒ 抬高之前请按自己库的容量核对，不放心就显式设回 20。
     */
    static final int DEFAULT_SCHEDULE_MAX_ACTIVE = 40;

    /** 属性源名字——同名重复注入必须先挡住，否则一个 {@code max-active} 会被后写的值改掉。 */
    static final String SCHEDULE_DEFAULT_SOURCE = "zScheduleDefaults";

    static void applySchedulePoolDefaults(Environment env) {
        if (!(env instanceof ConfigurableEnvironment)) {
            return;
        }
        ConfigurableEnvironment ce = (ConfigurableEnvironment) env;
        if (ce.getPropertySources().contains(SCHEDULE_DEFAULT_SOURCE)) {
            return;
        }
        Map<String, Object> defaults = new HashMap<String, Object>();
        defaults.put("z.base.db.schedule.max-active", DEFAULT_SCHEDULE_MAX_ACTIVE);
        // addLast = 最低优先级：任何宿主显式写的值都赢过这条默认
        ce.getPropertySources().addLast(new MapPropertySource(SCHEDULE_DEFAULT_SOURCE, defaults));
    }

    @Bean(name = "sqlSessionFactorySchedule")
    @ConditionalOnProperty(name = "z.base.db.schedule.disabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "sqlSessionFactorySchedule")
    public SqlSessionFactory sqlSessionFactorySchedule(
            @org.springframework.beans.factory.annotation.Qualifier("dataSourceSchedule")
            DataSource dataSourceSchedule) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSourceSchedule);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml"));
        return factoryBean.getObject();
    }
}
