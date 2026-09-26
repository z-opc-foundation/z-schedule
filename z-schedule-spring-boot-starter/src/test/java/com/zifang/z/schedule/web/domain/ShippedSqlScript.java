package com.zifang.z.schedule.web.domain;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取并执行随包建表脚本 {@code _doc/004_sql/z-schedule.sql} 的测试工具。
 * <p>
 * 存在的理由只有一个：需要建表的测试必须用**同一份会被发布的脚本**，而不是各自抄一段简化 DDL。
 * 抄出来的 DDL 会跟着测试作者的记忆漂——历史上"Unknown column"之所以只在现网首次访问才炸，
 * 就是因为没有任何东西把表结构和实体对过账。
 * <p>
 * 脚本失踪或解析出 0 条语句时直接抛 {@link IllegalStateException}：门禁不能因为读不到就静默通过。
 */
public final class ShippedSqlScript {

    private static final String RELATIVE_PATH =
            "_doc" + File.separator + "004_sql" + File.separator + "z-schedule.sql";

    private ShippedSqlScript() {
    }

    public static File script() {
        // surefire 的工作目录是模块 basedir，脚本在仓库根的 _doc 下
        File fromModule = new File("..", RELATIVE_PATH);
        if (fromModule.isFile()) {
            return fromModule;
        }
        File fromRoot = new File(RELATIVE_PATH);
        if (fromRoot.isFile()) {
            return fromRoot;
        }
        throw new IllegalStateException("找不到建表脚本 " + fromModule.getAbsolutePath()
                + " 或 " + fromRoot.getAbsolutePath() + "; 依赖它的测试不能静默跳过");
    }

    /** 去掉 {@code --} 注释行后按 {@code ;} 切句，返回非空语句列表。 */
    public static List<String> statements() throws Exception {
        String raw = new String(Files.readAllBytes(script().toPath()), StandardCharsets.UTF_8);
        StringBuilder body = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            body.append(line).append('\n');
        }
        List<String> out = new ArrayList<String>();
        for (String stmt : body.toString().split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("建表脚本解析出 0 条语句: " + script().getAbsolutePath());
        }
        return out;
    }

    /** 先清掉库里所有对象，再原样执行脚本——调用方拿到的就是首次部署后的表结构。 */
    public static void applyTo(Connection c) throws Exception {
        apply(c, statements());
    }

    public static void apply(Connection c, List<String> stmts) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS");
            for (String stmt : stmts) {
                s.execute(stmt);
            }
        }
    }
}
