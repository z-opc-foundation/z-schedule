package io.github.yuku123.z.schedule.web.config;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zifang.z.boot.datasource.starter.ModuleDataSourceTemplate;
import io.github.yuku123.z.schedule.core.config.ScheduleProperties;
import io.github.yuku123.z.schedule.web.filter.TokenAuthFilter;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

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
 */
@Configuration
@ComponentScan(basePackages = "io.github.yuku123.z.schedule.web")
@MapperScan(
        basePackages = "io.github.yuku123.z.schedule.web.domain.mapper",
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
     * Token 认证过滤器注册。
     * <p>
     * 当 z.schedule.accessToken 非空时启用，拦截 /executor/* 路径。
     */
    @Bean
    public FilterRegistrationBean<TokenAuthFilter> tokenAuthFilterRegistration(ScheduleProperties props) {
        FilterRegistrationBean<TokenAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TokenAuthFilter(props));
        registration.addUrlPatterns("/executor/*");
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
        return buildDataSource(env, "schedule");
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
