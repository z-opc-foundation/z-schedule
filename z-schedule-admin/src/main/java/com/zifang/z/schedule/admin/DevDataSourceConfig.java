package com.zifang.z.schedule.admin;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Dev profile DataSource 覆盖 —— 绕开 z-schedule-spring-boot-starter 的 MySQL 强制。
 *
 * <p>背景：ZScheduleAutoConfiguration 通过 {@code @Bean dataSourceSchedule} 注入一个
 * {@code ModuleDataSourceTemplate} 创建的 Druid DataSource，强制使用 MySQL driver + URL 模板。
 * 本地无 MySQL 时启动失败。
 *
 * <p>dev profile 下用本配置替换 starter 的 bean：
 * <ol>
 *   <li>{@link #removeStarterDataSourceBean()}：BeanDefinitionRegistryPostProcessor 在 Bean
 *       实例化前移除 starter 注册的 dataSourceSchedule Bean 定义</li>
 *   <li>{@link #dataSourceSchedule()}：同名 @Bean 提供 H2 DriverManagerDataSource</li>
 *   <li>{@link #sqlSessionFactorySchedule(DataSource)}：同名 @Bean 提供 MyBatis-Plus
 *       SqlSessionFactory，否则 starter 注册的 mapper Bean 找不到依赖</li>
 * </ol>
 *
 * <p>启动方式：{@code java -jar xxx.jar --spring.profiles.active=dev
 * --z.base.db.schedule.disabled=true}（禁用 starter 的 DataSource Bean 创建）。
 *
 * <p><b>作用范围</b>：仅 {@code dev} profile 生效。生产 / k8s 环境仍走 starter 的 MySQL 链路。
 *
 * @see com.zifang.z.schedule.web.config.ZScheduleAutoConfiguration
 */
@Configuration
@Profile("dev")
public class DevDataSourceConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DevDataSourceConfig.class);

    @Bean(name = "dataSourceSchedule")
    public DataSource dataSourceSchedule() {
        log.info(">>> DevDataSourceConfig.dataSourceSchedule() creating H2 in-memory DataSource");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:zschedule_dev;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * 接管 starter 的 sqlSessionFactorySchedule Bean 名，让 {@code @MapperScan} 注入的 mapper Bean
     * 能找到依赖。复用 starter 的 MybatisSqlSessionFactoryBean + 同一 mapper xml 路径。
     */
    @Bean(name = "sqlSessionFactorySchedule")
    public SqlSessionFactory sqlSessionFactorySchedule(
            @Qualifier("dataSourceSchedule") DataSource dataSourceSchedule) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSourceSchedule);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/**/*.xml"));
        return factoryBean.getObject();
    }
}