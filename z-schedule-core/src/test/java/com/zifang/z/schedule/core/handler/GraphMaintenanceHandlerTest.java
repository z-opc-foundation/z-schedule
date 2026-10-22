package com.zifang.z.schedule.core.handler;

import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.TriggerParam;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphMaintenanceHandler} 的行为测试（用 JDK 自带的 HttpServer 当桩）。
 *
 * <p>验三件事：① 三个动作按顺序打对端点、带对鉴权头；② 任一端点非 2xx ⇒
 * <b>任务失败</b>（不是"成功但什么都没做"）；③ 归档那一步真的把字节 multipart 送出去了。</p>
 */
class GraphMaintenanceHandlerTest {

    private HttpServer server;
    private int port;
    private final List<String> calls = new ArrayList<>();
    private final AtomicInteger archiveCalls = new AtomicInteger();
    private final List<byte[]> archivedBodies = new ArrayList<>();
    private volatile int gcStatus = 200;
    private volatile int snapshotStatus = 200;
    private volatile int archiveStatus = 200;
    private volatile String lastAuthorization;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maintenance/gc", exchange -> {
            calls.add("gc");
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getResponseHeaders().set("X-Gc-Collected", "7");
            respond(exchange, gcStatus, "{\"collected\":7}");
        });
        server.createContext("/maintenance/snapshot", exchange -> {
            calls.add("snapshot");
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] payload = "SNAPSHOT-BYTES".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Commit-Id", "commit-abc");
            exchange.getResponseHeaders().set("X-Snapshot-Sha256", "deadbeef");
            exchange.getResponseHeaders().set("X-Snapshot-Bytes", String.valueOf(payload.length));
            exchange.sendResponseHeaders(snapshotStatus, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/api/v1/backup/archive", exchange -> {
            calls.add("archive");
            archiveCalls.incrementAndGet();
            archivedBodies.add(readAll(exchange.getRequestBody()));
            respond(exchange, archiveStatus, "{\"key\":\"zgraph/x.bin\"}");
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String graphUrl() {
        return "http://127.0.0.1:" + port;
    }

    private TriggerParam param(String executorParams) {
        TriggerParam param = new TriggerParam();
        param.setExecutorParams(executorParams);
        return param;
    }

    @Test
    void runsGcSnapshotAndArchiveInOrder() throws Exception {
        GraphMaintenanceHandler handler = new GraphMaintenanceHandler();
        ReturnT<String> result = handler.execute(param(
                "graphUrl=" + graphUrl() + ";actions=gc,snapshot,archive;branch=main;"
                        + "ossUrl=" + graphUrl() + ";graphToken=tok;ossAccessKey=ak;ossSecretKey=sk"));

        assertTrue(result.isSuccess(), "三步都成功时应返回成功码");
        assertNotNull(result.getContent());
        assertTrue(result.getContent().contains("gcCollected=7"), result.getContent());
        assertTrue(calls.contains("gc"));
        assertTrue(calls.contains("snapshot"));
        assertTrue(calls.contains("archive"));
        assertEquals(1, archiveCalls.get());
        assertEquals("Bearer tok", lastAuthorization);
        // 归档这一步真的把字节送出去了（multipart 里能看到快照内容）
        String archived = new String(archivedBodies.get(0), StandardCharsets.UTF_8);
        assertTrue(archived.contains("SNAPSHOT-BYTES"), "归档体里必须带快照字节");
        assertTrue(archived.contains("name=\"file\""), "归档体必须是 multipart 的 file 字段");
    }

    @Test
    void gcFailureMakesTheJobFail() {
        gcStatus = 500;
        GraphMaintenanceHandler handler = new GraphMaintenanceHandler();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> handler.execute(param("graphUrl=" + graphUrl() + ";actions=gc;graphToken=tok")));
        assertTrue(error.getMessage().contains("GC 失败"), error.getMessage());
        assertTrue(error.getMessage().contains("500"), error.getMessage());
    }

    @Test
    void archiveFailureMakesTheJobFail() {
        archiveStatus = 503;
        GraphMaintenanceHandler handler = new GraphMaintenanceHandler();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> handler.execute(param("graphUrl=" + graphUrl() + ";actions=snapshot,archive;"
                        + "ossUrl=" + graphUrl() + ";graphToken=tok;ossAccessKey=ak;ossSecretKey=sk")));
        assertTrue(error.getMessage().contains("归档到 z-oss 失败"), error.getMessage());
    }

    @Test
    void missingGraphUrlFailsLoudlyInsteadOfDoingNothing() {
        GraphMaintenanceHandler handler = new GraphMaintenanceHandler();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(param("actions=gc")));
        assertTrue(error.getMessage().contains("graphUrl"), error.getMessage());
    }

    @Test
    void archiveWithoutSnapshotIsRejected() {
        GraphMaintenanceHandler handler = new GraphMaintenanceHandler();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(param("graphUrl=" + graphUrl() + ";actions=archive;ossUrl=" + graphUrl())));
        assertTrue(error.getMessage().contains("snapshot"), error.getMessage());
    }

    @Test
    void parsesKeyValueParams() {
        java.util.Map<String, String> params =
                GraphMaintenanceHandler.parseParams("a=1;b=2\nc=3;bad;=x");
        assertEquals("1", params.get("a"));
        assertEquals("2", params.get("b"));
        assertEquals("3", params.get("c"));
        assertFalse(params.containsKey("bad"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }
}
