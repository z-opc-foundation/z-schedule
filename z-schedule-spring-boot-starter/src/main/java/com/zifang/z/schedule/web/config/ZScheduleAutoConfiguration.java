package com.zifang.z.schedule.web.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zifang.z.boot.datasource.starter.ModuleDataSourceTemplate;
import com.zifang.z.schedule.core.config.ScheduleProperties;
import com.zifang.z.schedule.web.filter.TokenAuthFilter;
import com.zifang.z.schedule.web.service.AlarmService;
import com.zifang.z.schedule.web.service.impl.DefaultAlarmService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * <p><b>Dev profile 支持</b>：当 {@code z.base.db.schedule.disabled=true} 时，
 * {@link #dataSourceSchedule} Bean 不创建，由 admin 端提供 H2 等替代 DataSource（同名 @Bean）。
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
     * Token 认证过滤器注册。
     * <p>
     * 挂在 {@code /*} 上，由 {@link TokenAuthFilter} 自己按路径决定要不要校验：
     * 只注册 {@code /executor/*} 的话，管理面（建任务/删任务/看日志/改用户/dashboard/actuator）
     * 根本不会经过这个过滤器，配了 accessToken 也等于没配——过滤器里的路径判断会永远"通过"，
     * 测试再怎么断言 403 也照样绿。
     */
    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilterRegistration(ScheduleProperties props) {
        FilterRegistrationBean<TokenAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TokenAuthFilter(props));
        registration.addUrlPatterns("/*");
        registration.setName("tokenAuthFilter");
        registration.setOrder(1);
        return registration;
    }

    /**
     * 生产 / 默认环境：starter 自建 Druid + MySQL DataSource。
     * 用 {@code z.base.db.schedule.disabled=false}（或不设）启用。
     */
    @Bean(name = "dataSourceSchedule")
    @ConditionalOnProperty(name = "z.base.db.schedule.disabled", havingValue = "false", matchIfMissing = true)
    public DataSource dataSourceSchedule(Environment env) {
        applySchedulePoolDefaults(env);
        return buildDataSource(env, "schedule");
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
