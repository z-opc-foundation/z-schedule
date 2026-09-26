package com.zifang.z.schedule.web.config;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.web.service.AlarmService;
import com.zifang.z.schedule.web.service.JobTriggerService;
import com.zifang.z.schedule.web.service.impl.DefaultAlarmService;
import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link AlarmService} 扩展点的<b>装配层</b>验证。
 *
 * <p>这一层以前一个测试都没有：所有 service 测试都是 {@code new XxxServiceImpl()} + 反射塞字段，
 * 于是"宿主注册自己的告警实现"这种只在真实容器里才暴露的冲突，从没被测到过。
 * 生产侧 {@code JobTriggerServiceImpl} 用的是字段名就叫 {@code alarmService} 的 {@code @Resource}，
 * 所以这里的注入夹具刻意用同一个字段名——换个名字就等于没测。
 */
public class ZScheduleAlarmServiceWiringTest {

    private AnnotationConfigApplicationContext context;

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private void boot(Class<?>... configs) {
        context = new AnnotationConfigApplicationContext();
        context.register(configs);
        context.refresh();
    }

    /** 只有 starter 自己时，告警服务应该是内置兜底，且 bean 名必须是 alarmService。 */
    @Test
    public void 没有宿主实现时注册的是内置兜底() {
        boot(ZScheduleAutoConfiguration.AlarmServiceConfiguration.class);

        String[] names = context.getBeanNamesForType(AlarmService.class);
        assertEquals("AlarmService 应当恰好一个候选，实际=" + Arrays.toString(names), 1, names.length);
        assertEquals("bean 名必须是 alarmService：@Resource 靠字段名命中它来躲开多候选",
                "alarmService", names[0]);
        assertSame(DefaultAlarmService.class, context.getBean(AlarmService.class).getClass());
    }

    /** 宿主自带实现时，内置的那个必须让位——上下文里只剩宿主那一个。 */
    @Test
    public void 宿主自带实现时内置兜底必须退让() {
        boot(HostAlarmConfig.class, ZScheduleAutoConfiguration.AlarmServiceConfiguration.class);

        String[] names = context.getBeanNamesForType(AlarmService.class);
        assertEquals("内置兜底没退让，宿主会与它撞成两个候选：" + Arrays.toString(names), 1, names.length);
        assertEquals("hostAlarm", names[0]);
        assertNotNull(context.getBean(AlarmService.class));
    }

    /**
     * 条件判定没能退让的顺序（宿主 bean 在 starter 配置之后才注册）也必须能启动。
     *
     * <p>这是 {@code @Import} 路径下真实会发生的顺序：z-opc 的 main-starter 用
     * {@code @Import(ZScheduleAutoConfiguration.class)} 当 spring.factories 的安全网，
     * 被 @Import 的配置按普通配置解析，条件判定可能早于宿主自己的 @Bean。
     * 只要兜底的 bean 名与注入点字段名一致，{@code @Resource} 就还能按名锁定，
     * 不会抛 NoUniqueBeanDefinitionException 把宿主整个应用带走。
     */
    @Test
    public void 即使两个实现并存注入点也必须解析得出() {
        boot(ZScheduleAutoConfiguration.AlarmServiceConfiguration.class,
                LateHostAlarmConfig.class, InjectPoint.class);

        assertEquals("本例的前提就是两个候选并存", 2, context.getBeanNamesForType(AlarmService.class).length);

        InjectPoint probe = context.getBean(InjectPoint.class);
        assertSame("两个候选下 @Resource 应按字段名命中 alarmService，而不是启动失败",
                DefaultAlarmService.class, probe.alarmService.getClass());
    }

    /**
     * 结构守卫：兜底实现一旦被重新挂上 {@code @Service}，条件注解就救不回来了——
     * {@code @ComponentScan} 会在条件判定之前把它注册成 {@code defaultAlarmService}，
     * 于是"宿主注册即启动失败"这个原缺陷原地复活，而上面三个用例全都还是绿的
     * （它们只加载 {@code AlarmServiceConfiguration}，不跑 {@code ZScheduleAutoConfiguration} 那次扫描）。
     *
     * <p>所以这里直接复刻那次扫描：同一个包、同一个 {@code @Component} 类型过滤器，
     * 只数 bean 定义不 refresh（真起容器会被这一片 service 的依赖拖崩，测不到点上）。
     */
    @Test
    public void 兜底实现不得被组件扫描捡起() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry, false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        int found = scanner.scan("com.zifang.z.schedule.web.service.impl");

        assertTrue("扫描是空跑的：这个包里至少有一批 @Service，一个都没扫到说明尺坏了 found=" + found,
                found > 0);
        // 阳性对照：未 refresh 的工厂上"按类型数定义"这把尺本身要好用，
        // 否则下面那个 0 可能只是查询失灵。
        assertTrue("按类型查询在这套定义上查不到任何东西 ⇒ 尺不可信",
                registry.getBeanNamesForType(JobTriggerService.class, true, false).length >= 1);
        assertEquals("DefaultAlarmService 一旦被挂上组件注解，@ComponentScan 会抢在条件判定前注册它",
                0, registry.getBeanNamesForType(DefaultAlarmService.class, true, false).length);
    }

    /** 模拟宿主：注册一个自己的告警实现。 */
    @Configuration(proxyBeanMethods = false)
    public static class HostAlarmConfig {
        @Bean(name = "hostAlarm")
        public AlarmService hostAlarm() {
            return new HostAlarmService();
        }
    }

    /** 与宿主同类，但 bean 名刻意不叫 alarmService，用来构造"两个候选并存"。 */
    @Configuration(proxyBeanMethods = false)
    public static class LateHostAlarmConfig {
        @Bean(name = "lateHostAlarm")
        public AlarmService lateHostAlarm() {
            return new HostAlarmService();
        }
    }

    /** 与 {@code JobTriggerServiceImpl} 同形的注入点：字段名必须一致。 */
    @Component
    public static class InjectPoint {
        @Resource
        private AlarmService alarmService;
    }

    public static class HostAlarmService implements AlarmService {
        @Override
        public void sendAlarm(JobInfo job, JobLog alarmLog) {
            // 夹具，行为无关紧要
        }
    }
}
