package com.zifang.z.schedule.wiring;

// 夹具必须留在这个包里：ZScheduleAutoConfiguration 的 @ComponentScan 覆盖
// com.zifang.z.schedule.web 整个命名空间，宿主（以及本模块 target/test-classes）里
// 该包下任何带组件注解的类都会被扫成 bean。见 README §19。

import com.zifang.z.schedule.web.config.ZScheduleAutoConfiguration;
import com.zifang.z.schedule.web.auth.LoginSessionStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 宿主替身与 starter 那两支同名 {@code @Bean}（{@code dataSourceSchedule} /
 * {@code sqlSessionFactorySchedule}）的<b>装配层</b>验证。
 *
 * <p>缺陷原形：那两支只带 {@code @ConditionalOnProperty(z.base.db.schedule.disabled)}，没有
 * {@code @ConditionalOnMissingBean}。而 Boot 禁止同名 bean 覆盖是默认的（本类
 * {@code boot_默认禁止同名覆盖} 那一例真起一次容器量出来的，不是查文档），于是
 * "宿主自己提供同名 DataSource"这条路只要不额外设 {@code disabled=true} 就是
 * {@code BeanDefinitionOverrideException}。z-schedule-admin 的 dev profile 正是靠那面旗活的，
 * 而且必须同时给两支同名 bean，少一支起不来——文档一直把这个前置条件写成可选优化。
 *
 * <p>为什么全部走真容器：条件注解、同名冲突、注册顺序只在真实容器里存在，直接
 * {@code new ZScheduleAutoConfiguration()} 一个也测不到（{@code ZSchedulePoolDefaultTest} 那种
 * 直调法测的是取值，不是装配）。
 */
public class ZScheduleDataSourceOverrideTest {

    /** 宿主替身：只给一支同名 DataSource——"文档说的那样做"的形状。 */
    @Configuration(proxyBeanMethods = false)
    public static class HostDataSourceOnly {
        @Bean(name = "dataSourceSchedule")
        DataSource hostDs() {
            return h2("host_ds_only");
        }
    }

    /** 宿主只有一个<b>别的名字</b>的 DataSource：starter 那两支不许因此退让。 */
    @Configuration(proxyBeanMethods = false)
    public static class HostOtherName {
        @Bean(name = "dataSourceBusiness")
        DataSource businessDs() {
            return h2("business");
        }
    }

    private static DataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        return ds;
    }

    /** 按 Boot 默认禁掉同名覆盖，然后按给出的顺序注册配置类。 */
    private static AnnotationConfigApplicationContext ctxOf(Class<?>... configs) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        bf(ctx).setAllowBeanDefinitionOverriding(false);
        for (Class<?> c : configs) {
            ctx.register(c);
        }
        return ctx;
    }

    /** 只解析到"bean 定义全部登记完、还没实例化任何一个"那一刻，返回工厂供定义级断言。 */
    private static DefaultListableBeanFactory parseOnly(Class<?>... configs) {
        AnnotationConfigApplicationContext ctx = ctxOf(configs);
        ctx.registerBean(AbortAfterParse.class);
        try {
            ctx.refresh();
            throw new AssertionError("哨兵没生效：容器一路实例化到底了 ⇒ 读到的定义集合不可信");
        } catch (Abort expected) {
            return bf(ctx);
        } finally {
            close(ctx);
        }
    }

    /** 实例化之前停下来的哨兵。 */
    private static final class Abort extends RuntimeException {
        Abort() {
            super("sentinel", null, false, false);
        }
    }

    /** 不带 PriorityOrdered ⇒ 排在 {@code ConfigurationClassPostProcessor} 之后，定义已全部登记。 */
    public static class AbortAfterParse implements org.springframework.beans.factory.config.BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            throw new Abort();
        }
    }

    /** AnnotationConfigApplicationContext 的工厂实际就是这个类型。 */
    private static DefaultListableBeanFactory bf(AnnotationConfigApplicationContext ctx) {
        return (DefaultListableBeanFactory) ctx.getBeanFactory();
    }

    private static String factoryOf(BeanDefinition bd) {
        return String.valueOf(bd.getFactoryBeanName()) + '#' + bd.getFactoryMethodName();
    }

    private static void close(AnnotationConfigApplicationContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (Throwable ignore) {
                // 收尾失败不影响本轮读数
            }
        }
    }

    /**
     * 本类全部前提的地基：Boot 的 {@code allowBeanDefinitionOverriding} 默认就是 false。
     *
     * <p>假了会怎样：如果哪天默认翻回 true，本类那些"撞车"断言会全部变绿而缺陷早已不存在，
     * 而 {@code disabled=true} 那面旗也不再是前置条件——所以这一例读的是真容器的旗，不是记忆。
     */
    @Test
    public void boot_默认禁止同名覆盖() {
        ConfigurableApplicationContext ctx = null;
        try {
            ctx = new SpringApplicationBuilder(HostDataSourceOnly.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                    .run();
            assertFalse("Boot 若改成默许同名覆盖，本类的撞车断言就全部失效，要连带重审",
                    ((DefaultListableBeanFactory) ctx.getBeanFactory()).isAllowBeanDefinitionOverriding());
        } finally {
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    /**
     * 缺陷复现 + 修复兑现：宿主只给同名 DataSource、<b>不设</b> {@code disabled=true}，
     * starter 排在其后（= spring.factories 的真实顺序）时容器必须起得来，且用的是宿主那支。
     *
     * <p>修复前这一例是红的：{@code BeanDefinitionOverrideException}，被拒的定义是
     * {@code ZScheduleAutoConfiguration} 自己那一条。
     */
    @Test
    public void 宿主同名替身不必再设_disabled_就能起() {
        AnnotationConfigApplicationContext ctx = null;
        try {
            ctx = ctxOf(HostDataSourceOnly.class, ZScheduleAutoConfiguration.class);
            ctx.refresh();

            String[] names = ctx.getBeanNamesForType(DataSource.class);
            assertEquals("调度库的 DataSource 必须恰好一个，实际=" + Arrays.toString(names), 1, names.length);
            assertEquals("dataSourceSchedule", names[0]);
            assertTrue("替身必须真生效：应当是宿主的 H2，实际="
                            + ctx.getBean("dataSourceSchedule").getClass().getName(),
                    ctx.getBean("dataSourceSchedule") instanceof JdbcDataSource);
            assertEquals("starter 按名退让了 DataSource，但仍要自己建 SqlSessionFactory（建在宿主那支之上）",
                    1, ctx.getBeanNamesForType(org.apache.ibatis.session.SqlSessionFactory.class).length);
            assertEquals("调度域的 service 得在这支 DataSource 上装配起来",
                    1, ctx.getBeanNamesForType(com.zifang.z.schedule.web.service.JobInfoService.class).length);
        } finally {
            close(ctx);
        }
    }

    /** 按名退让 ≠ 按类型退让：宿主的 DataSource 名字不叫 {@code dataSourceSchedule} 时，starter 必须自建。 */
    @Test
    public void 宿主只有别的名字的_DataSource_starter_仍要自建() {
        DefaultListableBeanFactory bf = parseOnly(HostOtherName.class, ZScheduleAutoConfiguration.class);
        BeanDefinition ds = bf.getBeanDefinition("dataSourceSchedule");
        assertTrue("宿主没有同名替身时 starter 必须保留自己那支（写成按类型退让就是这个用例红）实际="
                + factoryOf(ds), factoryOf(ds).contains("ZScheduleAutoConfiguration"));
        assertTrue("同理 sqlSessionFactorySchedule 也必须留着，实际="
                        + factoryOf(bf.getBeanDefinition("sqlSessionFactorySchedule")),
                factoryOf(bf.getBeanDefinition("sqlSessionFactorySchedule"))
                        .contains("ZScheduleAutoConfiguration"));
    }

    /** 反方向：宿主给了同名替身时，留下的那条定义必须属于宿主，而不是"注册两次然后谁后谁赢"。 */
    @Test
    public void 宿主给了同名替身时_留下的定义必须是宿主那一支() {
        DefaultListableBeanFactory bf = parseOnly(HostDataSourceOnly.class, ZScheduleAutoConfiguration.class);
        String survivor = factoryOf(bf.getBeanDefinition("dataSourceSchedule"));
        assertFalse("starter 的 dataSourceSchedule 没退让，定义还是它的：" + survivor,
                survivor.contains("ZScheduleAutoConfiguration"));
        assertTrue("被留下的应当是宿主那个工厂方法 hostDs，实际=" + survivor, survivor.contains("hostDs"));
        assertTrue("starter 的 sqlSessionFactorySchedule 必须保留：它建在宿主那支 DataSource 之上",
                factoryOf(bf.getBeanDefinition("sqlSessionFactorySchedule"))
                        .contains("ZScheduleAutoConfiguration"));
    }

    /**
     * 钉住现状：走 {@code disabled=true} 这条路时，宿主<b>只给一支 DataSource 是起不来的</b>。
     *
     * <p>两条 {@code @ConditionalOnProperty} 一起把 {@code sqlSessionFactorySchedule} 也摘掉了，
     * 而类上的 {@code @MapperScan(sqlSessionFactoryRef = "sqlSessionFactorySchedule")} 是无条件的
     * ⇒ 五个 mapper 引用一个不存在的 bean。这正是"按名退让"才是可用扩展点的原因：
     * {@code disabled=true} 不是"少写一支 starter bean"的开关，它要求宿主把两支同名 bean 全补齐。
     */
    @Test
    public void 只设_disabled_true_而宿主只给一支_仍然起不来() {
        AnnotationConfigApplicationContext ctx = ctxOf(HostDataSourceOnly.class,
                ZScheduleAutoConfiguration.class);
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("z.base.db.schedule.disabled", true);
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("probe", props));
        try {
            ctx.refresh();
            fail("前提变了：这一形状已经能起来 ⇒ 要改成断言成功，并重新检查 @MapperScan 的引用还需要吗");
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertTrue("期望的根因是 sqlSessionFactorySchedule 这个 bean 不存在，实际="
                            + root.getClass().getName() + " :: " + root.getMessage(),
                    root instanceof org.springframework.beans.factory.NoSuchBeanDefinitionException
                            && String.valueOf(root.getMessage()).contains("sqlSessionFactorySchedule"));
        } finally {
            close(ctx);
        }
    }

    /**
     * 钉住边界：starter 排在宿主<b>之前</b>注册时（{@code @Import(ZScheduleAutoConfiguration.class)}
     * 这条路——被 @Import 的配置按普通配置先解析），条件注解救不了，被拒的是<b>宿主</b>那一条。
     *
     * <p>这一例不是"修好了的证明"，是残余缺口的机器可读记录：谁哪天把这一格也救活，就把断言改成
     * "起得来"。它值得留着，因为 z-opc 的 main-starter 真的在用 {@code @Import} 兜底。
     */
    @Test
    public void starter_先注册时条件注解救不了这一格() {
        AnnotationConfigApplicationContext ctx = ctxOf(ZScheduleAutoConfiguration.class,
                HostDataSourceOnly.class);
        try {
            ctx.refresh();
            fail("前提变了：@Import 顺序下也不撞车了 ⇒ 本例应改成断言起得来");
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertTrue("期望 BeanDefinitionOverrideException，实际="
                            + root.getClass().getName() + " :: " + root.getMessage(),
                    root instanceof org.springframework.beans.factory.support.BeanDefinitionOverrideException);
            assertTrue("被拒的必须是宿主那一条（否则这一例的归属判断没意义），消息=" + root.getMessage(),
                    String.valueOf(root.getMessage()).contains("HostDataSourceOnly"));
        } finally {
            close(ctx);
        }
    }

    /**
     * 结构守卫：两支的条件必须按 <b>name</b>，不许写成按类型。
     *
     * <p>写成 {@code @ConditionalOnMissingBean(DataSource.class)} 的话，
     * {@code 宿主同名替身...} 那一例照样绿（宿主那支确实是 DataSource），但真实宿主一旦自带主库，
     * 调度库就被静默摘掉——症状从"起不来"变成"起来了但没有调度域"，比原缺陷更难查。
     * 所以这里直接读注解本身。
     */
    @Test
    public void 两支条件必须是按名而不是按类型() throws Exception {
        assertNameOnly(ZScheduleAutoConfiguration.class
                .getMethod("dataSourceSchedule", Environment.class), "dataSourceSchedule");
        assertNameOnly(ZScheduleAutoConfiguration.class
                .getMethod("sqlSessionFactorySchedule", DataSource.class), "sqlSessionFactorySchedule");
    }

    private static void assertNameOnly(Method m, String beanName) {
        ConditionalOnMissingBean ann = m.getAnnotation(ConditionalOnMissingBean.class);
        assertNotNull(m.getName() + " 上没有 @ConditionalOnMissingBean ⇒ 宿主替身会撞车", ann);
        assertArrayEquals("必须按 bean 名退让（期望 name={" + beanName + "}）",
                new String[] { beanName }, ann.name());
        assertEquals("不许同时带类型：那会让宿主自带别的 DataSource 时调度库被静默摘掉",
                0, ann.value().length + ann.type().length);
    }

    /** 阳性对照：同一个类上确实存在"按类型"那种写法 ⇒ 上面那个 0 不是注解读不出来。 */
    @Test
    public void 阳性对照_同类里的_sessionStore_是按类型的() throws Exception {
        ConditionalOnMissingBean ann = ZScheduleAutoConfiguration.class
                .getMethod("sessionStore").getAnnotation(ConditionalOnMissingBean.class);
        assertNotNull(ann);
        assertArrayEquals("sessionStore 用的是类型退让 ⇒ 本类对注解的读法有效",
                new Class<?>[] { LoginSessionStore.class }, ann.value());
        assertEquals(0, ann.name().length);
    }
}
