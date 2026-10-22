package com.zifang.z.schedule.web.config;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 管理面裸路径闸门（{@code z.schedule.expose-bare-paths}）的<b>默认值与绑定</b>守卫。
 *
 * <p>为什么这一层要单独钉：这道闸门的默认值本身就是一份<b>安全承诺</b>——
 * starter 被嵌进聚合宿主（z-opc 那种单 JVM 聚合）时，管理面挂在根级，
 * 而宿主的登录闸门按路径前缀匹配（只挡 {@code api} 那一段）⇒ 根级裸路径不在射程内，
 * 匿名直达业务层（含写端点）。所以<b>默认必须是关</b>。
 *
 * <p>而独立部署的 {@code z-schedule-admin} 必须<b>显式开</b>：它自带前端与登录口，
 * 裸路径就是它的正式 API 形状（实测端点在根路径，{@code /jobinfo/list} 200）。
 * 这一条钉的是 admin 那份 yml 里真的写着 {@code z.schedule.expose-bare-paths=true}——
 * 哪天有人把它删了，admin 自己的 API 会被自家 starter 的闸门挡掉（而不是安静地继续工作）。
 */
public class ExposeBarePathsDefaultTest {

    /** 把一份属性源绑成 {@link ScheduleProperties}，走的是 starter 真正用的那个绑定器。 */
    private static ScheduleProperties bind(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("host", props));
        // 宿主一个 z.schedule.* 都没配时绑定结果是"没有值"而不是"绑出空对象"：
        // 这里取默认值，等价于 starter 没配任何东西时 ScheduleProperties 的字段默认值。
        return Binder.get(env).bind("z.schedule", ScheduleProperties.class)
                .orElseGet(ScheduleProperties::new);
    }

    @Test
    public void starter默认关闭裸路径() {
        // 宿主什么都没配 ⇒ 走 ScheduleProperties 的字段默认值
        assertFalse("starter 的默认必须是关闭裸路径：嵌进聚合宿主时它是安全默认",
                new ScheduleProperties().isExposeBarePaths());

        Map<String, Object> none = new HashMap<String, Object>();
        assertFalse("宿主没配 z.schedule.expose-bare-paths 时绑出来必须是 false",
                bind(none).isExposeBarePaths());
    }

    @Test
    public void 宿主显式开启时绑定生效() {
        Map<String, Object> on = new HashMap<String, Object>();
        on.put("z.schedule.expose-bare-paths", "true");
        assertTrue("显式开启必须能绑上（独立部署 admin 靠它保住裸路径）",
                bind(on).isExposeBarePaths());
    }

    /**
     * admin 的 yml 里必须真的开着这一项。
     *
     * <p>不读 yml 那一行字而读不到任何东西是不够的——这里用 Spring Boot <b>自己</b>的
     * {@link YamlPropertySourceLoader} 把那份 yml 加载成属性源（和启动时走的是同一条路），
     * 再用同一个绑定器绑一遍 ⇒ admin 启动时拿到的确实是 {@code true}。
     */
    @Test
    public void 独立部署的admin显式开启裸路径() throws IOException {
        Path adminYml = repoRoot().resolve("z-schedule-admin/src/main/resources/application.yml");
        assertTrue("找不到 admin 的 application.yml：" + adminYml, Files.isRegularFile(adminYml));

        String text = new String(Files.readAllBytes(adminYml), StandardCharsets.UTF_8);
        assertTrue("admin 的 application.yml 必须显式开启裸路径：它的端点就在根路径"
                        + "（实测 /jobinfo/list 200），闸门默认关着会把它自己的 API 全挡掉",
                text.contains("expose-bare-paths: true"));

        // 用 Spring Boot 启动时同一条加载路径读这份 yml，再用同一个绑定器绑一遍
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("admin-application.yml", new FileSystemResource(adminYml));
        assertFalse("这份 yml 竟然没加载出任何属性源", sources.isEmpty());

        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addLast(source);
        }
        ScheduleProperties bound = Binder.get(env).bind("z.schedule", ScheduleProperties.class)
                .orElseGet(ScheduleProperties::new);
        assertNotNull("绑定结果不该是 null", bound);
        assertTrue("admin 的 application.yml 必须让 z.schedule.expose-bare-paths 绑成 true"
                        + "（读数：z.schedule.expose-bare-paths="
                        + env.getProperty("z.schedule.expose-bare-paths") + "）",
                bound.isExposeBarePaths());
    }

    // ---- 工具 ----

    /** 仓根：向上找同时有 README.md 与 pom.xml 的目录（测试跑在模块目录，user.dir 不是仓根）。 */
    private static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("README.md")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
        }
        throw new IllegalStateException("定位仓根失败：user.dir=" + System.getProperty("user.dir"));
    }
}
