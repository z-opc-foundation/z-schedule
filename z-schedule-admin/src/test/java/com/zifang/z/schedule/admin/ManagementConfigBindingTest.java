package com.zifang.z.schedule.admin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "配置写了，但没有任何读者"这个病类的门禁。
 *
 * <p>起因是两处实测：{@code application.yml} 里整块 actuator 配置写作 {@code spring.actuator.*}
 * （缩进在 {@code spring:} 之下），而 Boot 读的是顶层 {@code management.*} ⇒ 250 真机上
 * {@code /actuator/info} 404、两条探针组路径 404，而 {@code deploy/k8s} 的探针就指那两条；
 * 同一文件里 {@code logging.level} 曾因包名从 {@code io.github.yuku123.z.schedule} 改成
 * {@code com.zifang.z.schedule} 而哑了一阵。两处都不是逻辑错，是**键落在没人读的位置上**，
 * 所以这里的断言全部针对"键的位置"而不是"值好看"。
 *
 * <p>不启动 Spring 上下文：本档只量 yml 的键形状，跑一次不该要 6 秒和一套库。
 */
class ManagementConfigBindingTest {

    private static EnumerablePropertySource<?> src;

    @BeforeAll
    static void load() throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        assertFalse(loaded.isEmpty(), "application.yml 在测试类路径上读不到（那这档就是空跑）");
        assertEquals(1, loaded.size(), "application.yml 应当只产出一个属性源，实际 " + loaded.size() + " 个");
        src = (EnumerablePropertySource<?>) loaded.get(0);
    }

    /** yml 里的标量可能是 Boolean/Integer，统一成字符串再比，断言消息也就原样可读。 */
    private static String str(String key) {
        Object v = src.getProperty(key);
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> keysUnder(String prefix) {
        List<String> out = new ArrayList<>();
        for (String k : src.getPropertyNames()) {
            if (k.startsWith(prefix)) out.add(k);
        }
        return out;
    }

    @Test
    @DisplayName("运维探针配置必须在顶层 management.* 下，且不得再出现在 spring.actuator.* 下")
    void probeConfigSitsWhereBootReadsIt() {
        assertTrue(keysUnder("management.endpoint.health.").size() >= 3,
                "顶层 management.endpoint.health.* 至少要钉住 probes/group/show-details，实际读到 "
                        + keysUnder("management.endpoint.health."));
        // 阳性对照在这一支：把块塞回 spring: 底下正是原缺陷的形状
        assertTrue(keysUnder("spring.actuator").isEmpty(),
                "spring.actuator.* 是 Boot 不读的位置（本仓 2026-09-26 之前的现场）：整块配置会静默失效，"
                        + "现在却有人以为它生效着。命中键=" + keysUnder("spring.actuator"));
    }

    @Test
    @DisplayName("暴露面钉死成 health,info：多一个端点都要过这道档")
    void exposureIsWhitelisted() {
        assertEquals("health,info", str("management.endpoints.web.exposure.include"),
                "这条值 250 实测过：写成 spring.actuator.* 时 /actuator/info 是 404，搬到顶层才 200；"
                        + "而 env/metrics 在两种情况下都是 404 ⇒ include 是白名单，放宽就是往外铺");
    }

    @Test
    @DisplayName("探针组必须显式打开：Boot 只在检测到 Kubernetes 平台时才自动建这两个组")
    void probeGroupsExistOffCluster() {
        assertEquals("true", str("management.endpoint.health.probes.enabled"),
                "不在集群里（本机 / 250 / docker-compose）时这两条路径天生 404，"
                        + "而 deploy/k8s/01-deployment-backend.yaml 的 livenessProbe/readinessProbe 指的就是它们"
                        + "⇒ 不打开就没有任何离集群的场合能验过部署文件承诺的探测");
    }

    @Test
    @DisplayName("readiness 必须含数据源，liveness 必须不含——两组的分工是唯一让它俩有意义的理由")
    void readinessAsksTheDbAndLivenessDoesNot() {
        String readiness = str("management.endpoint.health.group.readiness.include");
        String liveness = str("management.endpoint.health.group.liveness.include");
        assertNotNull(readiness, "readiness 组没显式定义，就会退回 Boot 的自动组");
        assertTrue(readiness.contains("db"),
                "实测缺陷（构件 a16473a，250）：把 spring.datasource.url 指到没人听的 33999 端口后，"
                        + "顶层 /actuator/health 正确 503 DOWN，而 /actuator/health/readiness 仍回 200 UP"
                        + "——照 manifest 部署时库死了 pod 继续接流量。现在 readiness=" + readiness);
        assertNotNull(liveness, "liveness 组没显式定义：它含 db 的话库一抖进程就被重启掉，reconcile 只会更糟");
        assertFalse(liveness.contains("db"),
                "liveness 里出现 db 就等于「数据库抖动 = 重启容器」，实际值=" + liveness);
    }

    @Test
    @DisplayName("匿名拿不到组件明细")
    void detailsAreNotPublic() {
        assertEquals("when-authorized", str("management.endpoint.health.show-details"),
                "演示模式（未配 z.schedule.accessToken）整个 HTTP 面敞开，而 DOWN 时的 detail 里是连接池内部状态"
                        + "（实测含 maxActive/active/creating 与驱动报错原文）");
    }

    @Test
    @DisplayName("logging.level 里每个包名前缀都必须在类路径上真存在")
    void everyLoggingLevelPackageIsReachable() {
        List<String> dead = new ArrayList<>();
        for (String k : keysUnder("logging.level.")) {
            String pkg = k.substring("logging.level.".length());
            if (pkg.isEmpty() || "root".equals(pkg)) continue;
            // 只有指向自家/依赖包的键会有读者；classpath 上找不到对应资源就是"写了没人读"的键
            if (ManagementConfigBindingTest.class.getClassLoader().getResource(pkg.replace('.', '/')) == null) {
                dead.add(k + "=" + str(k));
            }
        }
        assertTrue(dead.isEmpty(),
                "这些 logging.level 键指向类路径上不存在的包，等于静默失效（本仓 io.github.yuku123.z.schedule "
                        + "那一条就是这个形状，引擎 debug 日志哑了两周）：" + dead);
    }
}
