package com.zifang.z.schedule.wiring;

import org.junit.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 结构守卫：{@code ZScheduleAutoConfiguration} 的
 * {@code @ComponentScan("com.zifang.z.schedule.web")} 覆盖的是<b>整个命名空间</b>，
 * 而 classpath 里 {@code target/test-classes} 与 {@code target/classes} 是同一次编译的产物
 * ⇒ 谁把带组件注解的测试夹具放进 {@code com.zifang.z.schedule.web.*} 之下，starter 就会把它
 * 扫进<b>每一个</b>起这套自动配置的真实容器（宿主也一样）。
 *
 * <p>这一格不是假想：#41 那一轮里 {@code ZScheduleAlarmServiceWiringTest} 的嵌套
 * {@code @Configuration} 被扫进了别的用例的容器，原样报错
 * {@code NoUniqueBeanDefinitionException: ... found 2: hostAlarm,lateHostAlarm} 与
 * {@code Error creating bean with name 'ZScheduleAlarmServiceWiringTest.InjectPoint'}，
 * 三条用例因此红了个原因不明的红。修法是把装配测试挪到 {@code com.zifang.z.schedule.wiring}
 * ——挪完就没人拦着下一个人再放回来了，所以这里补尺（台账 §19.4-③）。
 *
 * <p>判据用的是<b>读字节码</b>而不是加载类：与 Spring 自己扫描时的做法一致
 * （{@code AnnotationTypeFilter(Component.class)} 也算元注解），且不会因为加载夹具把
 * 静态初始化副作用带进这个 JVM。
 */
public class ZScheduleScannedNamespaceTest {

    /** starter 自动配置实际扫描的包——改这里要连 {@code ZScheduleAutoConfiguration} 一起改。 */
    private static final String SCANNED_PACKAGE = "com.zifang.z.schedule.web";

    private static boolean fromTestClasses(Resource r) throws Exception {
        return r.getURL().toString().replace('\\', '/').contains("/target/test-classes/");
    }

    @Test
    public void 被扫描的命名空间里不许有带组件注解的测试夹具() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory mrf = new CachingMetadataReaderFactory(resolver);
        Resource[] all = resolver.getResources(
                "classpath*:" + SCANNED_PACKAGE.replace('.', '/') + "/**/*.class");

        int fixtureClasses = 0;
        List<String> offenders = new ArrayList<String>();
        for (Resource r : all) {
            if (!fromTestClasses(r)) {
                continue;
            }
            fixtureClasses++;
            AnnotationMetadata meta = mrf.getMetadataReader(r).getAnnotationMetadata();
            if (meta.hasAnnotation(Component.class.getName())
                    || meta.hasMetaAnnotation(Component.class.getName())) {
                offenders.add(meta.getClassName() + "  (" + r.getURL() + ")");
            }
        }

        // 阳性对照：夹具计数必须是"真扫到了一批"，否则下面那个 0 只是尺没跑起来。
        // 这一句同时保证：整棵测试树被挪空、或包名一改对不上 pattern，都会在这里响，而不是"通过"。
        assertTrue("尺坏了：在 target/test-classes 下 " + SCANNED_PACKAGE
                + " 里一个 .class 都没找到（测试树空了？pattern 对不上？）", fixtureClasses > 0);
        // 反向对照：同一个元数据读取器必须认得出 @Service 这类"元注解 @Component"的形状，
        // 否则上面的判据只对直挂 @Component 的夹具有效。真样本是 web/service/impl 下那批
        // @Service（DefaultAlarmService 是刻意不挂的，见 #22）——所以这里要求的是
        // "经元注解命中 ≥ 1"，而不是两种之和。
        int viaMeta = 0, direct = 0;
        for (Resource prod : resolver.getResources(
                "classpath*:" + SCANNED_PACKAGE.replace('.', '/') + "/service/impl/*.class")) {
            if (fromTestClasses(prod)) {
                continue;
            }
            AnnotationMetadata m = mrf.getMetadataReader(prod).getAnnotationMetadata();
            if (m.hasMetaAnnotation(Component.class.getName())
                    && !m.hasAnnotation(Component.class.getName())) {
                viaMeta++;
            } else if (m.hasAnnotation(Component.class.getName())) {
                direct++;
            }
        }
        assertTrue("元注解这条判据没有真样本可判（web/service/impl 下 @Service 经元注解命中 0 个，"
                + "直挂 " + direct + " 个）⇒ 尺不可信", viaMeta > 0);

        // 两个分母要能读出来：绿跑里"扫到多少"是唯一能事后核对尺有没有跑起来的数。
        System.out.println("NSPROBE fixtureClasses=" + fixtureClasses
                + " prodViaMeta=" + viaMeta + " prodDirect=" + direct
                + " offenders=" + offenders.size());


        if (!offenders.isEmpty()) {
            fail("这些测试夹具带着组件注解，会被 starter 的 @ComponentScan(\""
                    + SCANNED_PACKAGE + "\") 扫进真实容器（含宿主）：\n  "
                    + join(offenders)
                    + "\n装配测试请放到扫描面之外的包（本文件所在的 com.zifang.z.schedule.wiring 就是为此存在的）。");
        }
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            sb.append(x).append("\n  ");
        }
        return sb.toString().trim();
    }
}
