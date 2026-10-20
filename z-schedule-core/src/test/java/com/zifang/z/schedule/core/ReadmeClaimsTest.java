package com.zifang.z.schedule.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README 里每一条定量主张都必须能从盘面重算出来 —— 广告出去的话就是承诺。
 *
 * <p>为什么要有这一层：本仓 README 把「7 个 REST Controller」「10 个 ExecutorRouter」
 * 「i18n 各 6 个 key」「当前版本 1.0.6」写成了硬数字，而每加一个 Controller、
 * 每加一个 Router 都会漂一次。没有任何尺子去问盘面「现在还是吗」，
 * 那这些数字的保质期就是上一次编辑的时刻。</p>
 *
 * <p>口径：缺一条主张 = 红（不许「正则没匹配上就跳过」，那正是空跑）；
 * 数值不等 = 红且把<b>重算值</b>打进消息（红了要知道真数是多少，否则只是又一处猜）。
 * 尺自己的牙口由 {@link #rulerActuallyCondemnsWrongNumbers()} 用合成 README 证明，
 * 而「不许冤枉健康页」由 {@link #readmeClaimsMatchTheCode()} 本身证明。</p>
 */
class ReadmeClaimsTest {

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

    private static String read(Path f) {
        try {
            return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String norm(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** 仓内全部 java 源文件（排除构建产物、node_modules 与参考树）。 */
    private static List<Path> javaSources(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !norm(p).contains("/target/"))
                    .filter(p -> !norm(p).contains("/node_modules/"))
                    .filter(p -> !norm(p).contains("/.git/"))
                    .filter(p -> !norm(p).contains("/_code_temp/"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 某个<b>相对路径</b>目录下直属的 .java 个数。
     *
     * <p>为什么用相对路径而不是目录名：本仓有 4 个名为 {@code impl} 的目录
     * （core/route/impl 10 个 + starter 的 web/service/impl 9 个，外加两个 src/test 下的），
     * 按目录名统计会得到 19 —— 而 README 说的「10 个 ExecutorRouter」指的是
     * {@code z-schedule-core/.../core/route/impl} 这一处。**多模块仓里目录名计数不可靠**，
     * 一律用相对路径定位。</p>
     */
    private static long countJavaIn(Path root, String relativeDir) {
        Path d = root.resolve(relativeDir);
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("目录不存在：" + relativeDir);
        }
        try (Stream<Path> c = Files.list(d)) {
            return c.filter(f -> f.toString().endsWith(".java")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 资源文件里的 key 数（形如 {@code key=value} 的行）。 */
    private static int propertyKeyCount(Path f) {
        if (!Files.isRegularFile(f)) {
            throw new IllegalStateException("资源文件不存在：" + f);
        }
        int n = 0;
        for (String line : read(f).split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#") && s.contains("=")) {
                n++;
            }
        }
        return n;
    }

    /** 从 xml 里取某个标签的文本值。 */
    private static String tag(Path f, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + ">([^<]+)</" + Pattern.quote(name) + ">")
                .matcher(read(f));
        if (!m.find()) {
            throw new IllegalStateException(f + " 里没有 <" + name + ">");
        }
        return m.group(1);
    }

    /** 一次重算出的全部数字。 */
    private static final class Counts {
        final long controllers;
        final long routers;
        final int i18nKeysZh;
        final int i18nKeysEn;
        final String revision;
        final String adminConsumedVersion;

        Counts(long controllers, long routers, int i18nKeysZh, int i18nKeysEn,
               String revision, String adminConsumedVersion) {
            this.controllers = controllers;
            this.routers = routers;
            this.i18nKeysZh = i18nKeysZh;
            this.i18nKeysEn = i18nKeysEn;
            this.revision = revision;
            this.adminConsumedVersion = adminConsumedVersion;
        }
    }

    private static Counts recomputed() {
        Path root = repoRoot();
        List<Path> src = javaSources(root);
        Path i18n = root.resolve("z-schedule-spring-boot-starter/src/main/resources/i18n");
        return new Counts(
                src.stream().filter(p -> p.getFileName().toString().endsWith("Controller.java")).count(),
                countJavaIn(root, "z-schedule-core/src/main/java/com/zifang/z/schedule/core/route/impl"),
                propertyKeyCount(i18n.resolve("messages_zh_CN.properties")),
                propertyKeyCount(i18n.resolve("messages_en.properties")),
                tag(root.resolve("pom.xml"), "revision"),
                tag(root.resolve("z-schedule-admin/pom.xml"), "z-schedule.version"));
    }

    /** README 里逐条抠出数字并与重算值对账；不一致就抛 AssertionError 并把真数打进去。 */
    private static void verifyClaims(String readme, Counts c) {
        Matcher m;

        // 主张 1：「连同 7 个 REST Controller 直接装进宿主」
        m = Pattern.compile("连同 (\\d+) 个 REST Controller").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 里找不到「连同 N 个 REST Controller」这条主张");
        }
        assertEquals(c.controllers, Long.parseLong(m.group(1)), "REST Controller 数对不上（全仓 *Controller.java）");

        // 主张 2：「| **当前版本** | `1.0.6`（根 POM `<revision>`…」
        m = Pattern.compile("\\| \\*\\*当前版本\\*\\* \\| `([^`]+)`").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 里找不到「| **当前版本** | `X.Y.Z`」这条主张");
        }
        assertEquals(c.revision, m.group(1), "版本号对不上（根 POM <revision>）");

        // 主张 3：「**10 个** `ExecutorRouter` 实现」
        m = Pattern.compile("\\*\\*(\\d+) 个\\*\\* `ExecutorRouter` 实现").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 里找不到「**N 个** `ExecutorRouter` 实现」这条主张");
        }
        assertEquals(c.routers, Long.parseLong(m.group(1)), "Router 实现数对不上（core/route/impl 下的 .java 个数）");

        // 主张 4：「`z-schedule-core` 里 10 个 router 是已发布 API」
        m = Pattern.compile("里 (\\d+) 个 router").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 里找不到「里 N 个 router」这条主张");
        }
        assertEquals(c.routers, Long.parseLong(m.group(1)), "Router 实现数对不上（README 两处都写了同一个数）");

        // 主张 5：「`i18n/messages_zh_CN.properties` 与 `messages_en.properties` 各 6 个 key」
        m = Pattern.compile("各 (\\d+) 个 key").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 里找不到「各 N 个 key」这条主张");
        }
        int keys = Integer.parseInt(m.group(1));
        assertEquals(c.i18nKeysZh, keys, "messages_zh_CN.properties 的 key 数对不上");
        assertEquals(c.i18nKeysEn, keys, "messages_en.properties 的 key 数对不上");

        // 主张 6：「另用一条 `<z-schedule.version>` 属性（现值 `1.0.6`）」
        m = Pattern.compile("`<z-schedule\\.version>` 属性（现值 `([^`]+)`）").matcher(readme);
        if (!m.find()) {
            throw new AssertionError("README 里找不到「`<z-schedule.version>` 属性（现值 `X.Y.Z`）」这条主张");
        }
        assertEquals(c.adminConsumedVersion, m.group(1),
                "z-schedule-admin 消费的版本对不上（z-schedule-admin/pom.xml 的 <z-schedule.version>）");
    }

    @Test
    @DisplayName("README 的定量主张与盘面重算值逐条对账")
    void readmeClaimsMatchTheCode() {
        Path root = repoRoot();
        Counts c = recomputed();
        verifyClaims(read(root.resolve("README.md")), c);
    }

    @Test
    @DisplayName("尺子的牙口：把 README 的数改错，判定必须判红")
    void rulerActuallyCondemnsWrongNumbers() {
        String readme = read(repoRoot().resolve("README.md"));
        String tampered = readme.replaceFirst("\\*\\*10 个\\*\\* `ExecutorRouter`", "**9 个** `ExecutorRouter`");
        assertNotEquals(readme, tampered, "篡改失败：README 里找不到「**10** `ExecutorRouter`」字样（写法变了？）");
        AssertionError err = assertThrows(AssertionError.class,
                () -> verifyClaims(tampered, recomputed()));
        assertTrue(err.getMessage().contains("Router"), "报错要指向真正对不上的那条主张，实际是：" + err.getMessage());
    }

    @Test
    @DisplayName("不许冤枉健康页：真实 README 不得被判红")
    void healthyReadmeIsNotCondemned() {
        String readme = read(repoRoot().resolve("README.md"));
        verifyClaims(readme, recomputed());
    }
}
