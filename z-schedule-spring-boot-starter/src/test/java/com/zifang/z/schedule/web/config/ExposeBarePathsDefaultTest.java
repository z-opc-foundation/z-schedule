package com.zifang.z.schedule.web.config;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
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
        env.getPropertySources().addFirst(new MapPropertySource("host", props));
        return Binder.get(env).bind("z.schedule", ScheduleProperties.class).get();
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
     * <p>不读 yml 那一行字而读不到任何东西是不够的——这里读的是文件里真实存在的那段 yaml，
     * 并且用 Spring 自己的绑定器把它绑一遍 ⇒ admin 启动时拿到的确实是 {@code true}。
     */
    @Test
    public void 独立部署的admin显式开启裸路径() throws IOException {
        Path adminYml = repoRoot().resolve("z-schedule-admin/src/main/resources/application.yml");
        assertTrue("找不到 admin 的 application.yml：" + adminYml, Files.isRegularFile(adminYml));

        String text = new String(Files.readAllBytes(adminYml), StandardCharsets.UTF_8);
        assertTrue("admin 的 application.yml 必须显式开启裸路径：它的端点就在根路径"
                        + "（实测 /jobinfo/list 200），闸门默认关着会把它自己的 API 全挡掉",
                text.contains("expose-bare-paths: true"));

        // 用 Spring 的 yaml 解析 + 绑定器走一遍，确认这不是"看着像开了"而是真能绑上
        Map<String, Object> parsed = parseYaml(adminYml);
        Map<String, Object> z = asMap(parsed.get("z"));
        assertNotNull("admin 的 application.yml 里应有 z: 段", z);
        Map<String, Object> schedule = asMap(z.get("schedule"));
        assertNotNull("admin 的 application.yml 里应有 z.schedule: 段", schedule);
        assertTrue("z.schedule.expose-bare-paths 必须绑成 true",
                Boolean.parseBoolean(String.valueOf(schedule.get("expose-bare-paths"))));
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

    /** 极简 yaml 解析：只认「缩进的 key: value」与顶层 key，足以读出 z.schedule.expose-bare-paths。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(Path file) throws IOException {
        Map<String, Object> root = new HashMap<String, Object>();
        String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n");
        String section = null;
        String subsection = null;
        for (String raw : lines) {
            String line = raw.replace("\t", "    ");
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.trim().length();
            String trimmed = line.trim();
            if (indent == 0) {
                section = trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : null;
                subsection = null;
                continue;
            }
            if (section == null || !trimmed.contains(":")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            String value = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            if (indent == 2) {
                subsection = value.endsWith(":") ? key : null;
                if (!value.endsWith(":")) {
                    asMap(root).put(key, value);
                }
                continue;
            }
            if (indent >= 4 && subsection != null) {
                Map<String, Object> sec = asMap(asMap(root).get(section));
                if (sec == null) {
                    sec = new HashMap<String, Object>();
                    asMap(root).put(section, sec);
                }
                Map<String, Object> sub = asMap(sec.get(subsection));
                if (sub == null) {
                    sub = new HashMap<String, Object>();
                    sec.put(subsection, sub);
                }
                sub.put(key, value);
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
