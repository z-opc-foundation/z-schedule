package com.zifang.z.schedule.admin;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 *   <li>{@link #dataSourceSchedule()}：同名 @Bean 提供 H2 DriverManagerDataSource</li>
 *   <li>{@link #sqlSessionFactorySchedule(DataSource)}：同名 @Bean 提供 MyBatis-Plus
 *       SqlSessionFactory，否则 starter 注册的 mapper Bean 找不到依赖</li>
 * </ol>
 *
 * <p>启动方式：{@code java -jar xxx.jar --spring.profiles.active=dev}（不需要再给
 * {@code --z.base.db.schedule.disabled=true}）。这条"不用给"是本树的行为：starter 的两支同名
 * {@code @Bean} 带 {@code @ConditionalOnMissingBean(name = …)}，按名退让给本类。
 * 仍要给它的是两条旧路——已发布的 1.0.4 字节没有按名退让，而 {@code cd z-schedule-admin && mvn
 * spring-boot:run} 的依赖正由 {@code ~/.m2} 解析（2026-09-27 四条命令逐条实测，读数在
 * {@code _doc/005_testing/e2e/README.md} §17.1，修复前后的成对读数在 §19）。
 * 反过来，{@code disabled=true} 也不是"少给一支 bean"的开关：它把两支一起摘掉，而类上那句
 * {@code @MapperScan} 引用 {@code sqlSessionFactorySchedule} 是无条件的 ⇒ 设了旗却只补
 * {@code DataSource} 这一格两版字节都起不来。
 * 本类的旧 javadoc 还列过一个
 * {@code removeStarterDataSourceBean()}（说它用 BeanDefinitionRegistryPostProcessor 提前摘掉
 * starter 的定义）—— 那个方法<b>从来没被写进过这个文件</b>（{@code git log -S} 追到建文件那一版
 * c8e3a8f 也只有 javadoc 里这一处），连带四个 import 一起是空口机制，已删。
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