package com.zifang.z.schedule.core.handler;

import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.TriggerParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图库维护任务（GC + 快照 + 备份归档）—— TASK-20261012-027。
 *
 * <p>由 z-schedule 定时派发，一次跑完三件事（都可用 {@code actions} 挑）：
 * <ol>
 *   <li>{@code gc} —— 打 z-graph 的 {@code POST /maintenance/gc}（版本链回收），
 *       <b>keep 参数留空 = 保留全部分支</b>；</li>
 *   <li>{@code snapshot} —— 打 z-graph 的 {@code POST /maintenance/snapshot} 导出快照；</li>
 *   <li>{@code archive} —— 把快照字节 multipart 交给 z-oss 的
 *       {@code POST /api/v1/backup/archive}（归档 + 滚动保留 N 份）。</li>
 * </ol>
 *
 * <p><b>失败不静默</b>：任一步非 2xx 或异常 ⇒ 抛出去并返回 {@code ReturnT.fail}，
 * 调度侧会把它记成一次<b>任务失败</b>（不会回写成"成功但什么都没做"）。</p>
 *
 * <p><b>参数</b>（{@code executorParams}，{@code key=value} 以 {@code ;} 或换行分隔）：
 * <pre>
 * graphUrl=http://z-graph:8090     # z-graph 控制面基址
 * actions=gc,snapshot,archive      # 默认 gc
 * keep=                             # GC 保留分支（逗号分隔）；空 = 保留全部分支
 * branch=main                      # 快照分支
 * ossUrl=http://z-oss:8091         # z-oss 基址（archive 用）
 * timeoutMs=60000
 * </pre>
 * <b>凭据不进参数</b>：z-graph 的 API Token 取环境变量 {@code Z_GRAPH_API_TOKEN}，
 * z-oss 的 AK/SK 取 {@code ZOSS_ACCESS_KEY} / {@code ZOSS_SECRET_KEY}
 * （参数可以覆盖，但生产建议只用环境变量 —— 任务参数会落库）。</p>
 */
public class GraphMaintenanceHandler implements IJobHandler {

    private static final Logger log = LoggerFactory.getLogger(GraphMaintenanceHandler.class);

    private static final String BOUNDARY = "----zScheduleBoundary" + Long.toHexString(System.nanoTime());

    @Override
    public ReturnT<String> execute(TriggerParam triggerParam) throws Exception {
        Map<String, String> params = parseParams(triggerParam == null ? null : triggerParam.getExecutorParams());
        List<String> actions = splitList(params.get("actions"), "gc");
        String graphUrl = trimTrailingSlash(params.get("graphUrl"));
        String graphToken = firstNonBlank(params.get("graphToken"), System.getenv("Z_GRAPH_API_TOKEN"));
        String ossUrl = trimTrailingSlash(params.get("ossUrl"));
        String ossAccessKey = firstNonBlank(params.get("ossAccessKey"), System.getenv("ZOSS_ACCESS_KEY"));
        String ossSecretKey = firstNonBlank(params.get("ossSecretKey"), System.getenv("ZOSS_SECRET_KEY"));
        String branch = params.getOrDefault("branch", "main");
        String keep = params.getOrDefault("keep", "").trim();
        int timeoutMs = parseInt(params.get("timeoutMs"), 60000);

        Map<String, String> report = new LinkedHashMap<>();
        report.put("actions", String.join(",", actions));

        byte[] snapshot = null;
        if (actions.contains("gc")) {
            require(graphUrl != null, "动作 gc 需要参数 graphUrl");
            require(graphToken != null, "动作 gc 需要 z-graph API Token（参数 graphToken 或环境变量 Z_GRAPH_API_TOKEN）");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + graphToken);
            String url = graphUrl + "/maintenance/gc" + (keep.isEmpty() ? "" : "?keep=" + urlEncode(keep));
            HttpResult result = request(url, "POST", null, null, headers, timeoutMs);
            require2xx(result, "GC");
            String collected = result.header("X-Gc-Collected");
            report.put("gcCollected", collected == null ? "?" : collected);
            log.info("[graph-maintenance] GC 完成: 回收 commit={}，keep={}", collected, keep.isEmpty() ? "(全部分支)" : keep);
        }

        if (actions.contains("snapshot")) {
            require(graphUrl != null, "动作 snapshot 需要参数 graphUrl");
            require(graphToken != null, "动作 snapshot 需要 z-graph API Token（参数 graphToken 或环境变量 Z_GRAPH_API_TOKEN）");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + graphToken);
            boolean needBytes = actions.contains("archive");
            String url = graphUrl + "/maintenance/snapshot?branch=" + urlEncode(branch)
                    + (needBytes ? "&stream=true" : "");
            HttpResult result = request(url, "POST", null, null, headers, timeoutMs);
            require2xx(result, "快照导出");
            report.put("snapshotCommitId", nullToDash(result.header("X-Commit-Id")));
            report.put("snapshotSha256", nullToDash(result.header("X-Snapshot-Sha256")));
            report.put("snapshotBytes", nullToDash(result.header("X-Snapshot-Bytes")));
            if (needBytes) {
                snapshot = result.body;
                if (snapshot == null || snapshot.length == 0) {
                    throw new IllegalStateException("快照字节为空 —— 归档无从谈起");
                }
            }
            log.info("[graph-maintenance] 快照导出完成: branch={}，commit={}，sha256={}",
                    branch, report.get("snapshotCommitId"), report.get("snapshotSha256"));
        }

        if (actions.contains("archive")) {
            require(snapshot != null, "动作 archive 需要先跑 snapshot（actions 必须同时含 snapshot,archive）");
            require(ossUrl != null, "动作 archive 需要参数 ossUrl");
            require(ossAccessKey != null && ossSecretKey != null,
                    "动作 archive 需要 z-oss 凭据（环境变量 ZOSS_ACCESS_KEY / ZOSS_SECRET_KEY）");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Zoss-Access-Key", ossAccessKey);
            headers.put("X-Zoss-Secret-Key", ossSecretKey);
            byte[] body = multipartBody("file", "zgraph-snapshot.bin", snapshot);
            HttpResult result = request(ossUrl + "/api/v1/backup/archive", "POST", body,
                    "multipart/form-data; boundary=" + BOUNDARY, headers, timeoutMs);
            require2xx(result, "归档到 z-oss");
            report.put("archive", "ok");
            log.info("[graph-maintenance] 归档完成: 字节={}", snapshot.length);
        }

        String summary = report.toString();
        log.info("[graph-maintenance] 任务完成: {}", summary);
        return ReturnT.success(summary);
    }

    // ==================== HTTP ====================

    /** 一次 HTTP 调用的结果（状态码 + 头 + 字节）。 */
    private static final class HttpResult {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        HttpResult(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private static HttpResult request(String url, String method, byte[] body, String contentType,
                                      Map<String, String> headers, int timeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            byte[] responseBody = readAll(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    responseHeaders.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
                }
            }
            return new HttpResult(status, responseHeaders, responseBody);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /** 拼 multipart/form-data 请求体（file 字段 + 文件名）。 */
    private static byte[] multipartBody(String fieldName, String fileName, byte[] file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ==================== 小工具 ====================

    /** 解析 {@code key=value;key2=value2}（{@code ;} 或换行分隔）。 */
    static Map<String, String> parseParams(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return params;
        }
        for (String part : raw.split("[;\\r\\n]")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
        }
        return params;
    }

    private static List<String> splitList(String raw, String fallback) {
        List<String> items = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String part : raw.split(",")) {
                String item = part.trim();
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
        }
        if (items.isEmpty()) {
            items.add(fallback);
        }
        return items;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void require2xx(HttpResult result, String what) {
        if (result.status < 200 || result.status >= 300) {
            String body = result.body == null ? "" : new String(result.body, StandardCharsets.UTF_8);
            if (body.length() > 500) {
                body = body.substring(0, 500) + "...";
            }
            throw new IllegalStateException(what + " 失败: HTTP " + result.status + " " + body);
        }
    }
}
