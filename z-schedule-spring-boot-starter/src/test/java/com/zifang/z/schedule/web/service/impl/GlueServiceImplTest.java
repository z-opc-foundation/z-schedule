package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.enums.GlueTypeEnum;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.controller.GlueController;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * GLUE 源码仓储的契约：版本顺序、保留窗口、入参校验、以及并发读写的快照一致性。
 * <p>
 * 这份实现只活在 JVM 堆里（不落库、集群各节点各存一份），所以它必须至少做到：
 * 读到的永远是某个完整快照，写入被拒时不留半成品。
 */
public class GlueServiceImplTest {

    private GlueServiceImpl glue;

    @Before
    public void setUp() {
        glue = new GlueServiceImpl();
    }

    // ---- 读取与版本窗口 ----

    @Test
    public void 保存后取回最新源码() {
        assertEquals(200, glue.save(1, "echo v1", GlueTypeEnum.GLUE_SHELL.getCode()).getCode());
        assertEquals("echo v1", glue.get(1).getContent());

        glue.save(1, "echo v2", GlueTypeEnum.GLUE_SHELL.getCode());
        assertEquals("最新版本必须覆盖旧版本", "echo v2", glue.get(1).getContent());
    }

    @Test
    public void 版本列表从新到旧排列() {
        for (String source : new String[]{"s1", "s2", "s3"}) {
            glue.save(7, source, GlueTypeEnum.GLUE_JAVA.getCode());
        }
        List<String> versions = glue.getVersions(7).getContent();
        assertEquals(java.util.Arrays.asList("s3", "s2", "s1"), versions);
    }

    @Test
    public void 只保留最近30个版本() {
        for (int i = 1; i <= 35; i++) {
            glue.save(3, "v" + i, GlueTypeEnum.GLUE_SHELL.getCode());
        }

        List<String> versions = glue.getVersions(3).getContent();
        assertEquals(30, versions.size());
        assertEquals("v35", versions.get(0));
        assertEquals("v6", versions.get(29));
        assertFalse("最旧的 5 版应被挤掉", versions.contains("v5"));
    }

    @Test
    public void 不同任务之间的版本互不干扰() {
        glue.save(1, "job-1", GlueTypeEnum.GLUE_SHELL.getCode());
        glue.save(2, "job-2", GlueTypeEnum.GLUE_SHELL.getCode());

        assertEquals(Collections.singletonList("job-1"), glue.getVersions(1).getContent());
        assertEquals("job-2", glue.get(2).getContent());
    }

    @Test
    public void 返回的版本列表是快照改不动内部状态() {
        glue.save(5, "keep", GlueTypeEnum.GLUE_SHELL.getCode());

        List<String> handed = glue.getVersions(5).getContent();
        handed.add("injected");
        handed.clear();

        assertEquals(Collections.singletonList("keep"), glue.getVersions(5).getContent());
        assertEquals("keep", glue.get(5).getContent());
    }

    // ---- 入参校验 ----

    @Test
    public void 非法任务ID一律拒绝且不留副作用() {
        for (int jobId : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertEquals("jobId=" + jobId, 500, glue.save(jobId, "src", GlueTypeEnum.GLUE_SHELL.getCode()).getCode());
            assertEquals("jobId=" + jobId, 500, glue.get(jobId).getCode());
            assertEquals("jobId=" + jobId, 500, glue.getVersions(jobId).getCode());
        }

        glue.save(1, "src", GlueTypeEnum.GLUE_SHELL.getCode());
        assertEquals(500, glue.save(1, null, GlueTypeEnum.GLUE_SHELL.getCode()).getCode());
        assertEquals("坏请求不得覆盖已保存的源码", "src", glue.get(1).getContent());
        assertEquals("失败也不该占用一个版本位", 1, glue.getVersions(1).getContent().size());
    }

    @Test
    public void 纯空白源码视为空源码() {
        assertEquals(500, glue.save(1, "", GlueTypeEnum.GLUE_SHELL.getCode()).getCode());
        assertEquals(500, glue.save(1, "   \n\t ", GlueTypeEnum.GLUE_SHELL.getCode()).getCode());
        assertEquals("空白源码不得入版本库", 500, glue.get(1).getCode());
        assertTrue(glue.getVersions(1).getContent().isEmpty());
    }

    @Test
    public void 四种GLUE类型都可保存() {
        for (GlueTypeEnum type : GlueTypeEnum.values()) {
            assertEquals(type.getCode(), 200, glue.save(9, "body", type.getCode()).getCode());
            assertEquals(type.getCode(), 200, glue.get(9).getCode());
        }
    }

    @Test
    public void 类型必须精确匹配已登记的编码() {
        ReturnT<String> rejected = glue.save(1, "src", "GLUE(JAVA)");
        assertEquals(500, rejected.getCode());
        assertTrue("错误信息要点明类型不合法: " + rejected.getMsg(), rejected.getMsg().contains("GLUE类型不合法"));

        assertEquals(500, glue.save(1, "src", null).getCode());
        assertEquals(500, glue.save(1, "src", "").getCode());
    }

    @Test
    public void 未保存过的任务get失败但版本列表为空() {
        assertEquals("GLUE源码不存在", 500, glue.get(100).getCode());
        ReturnT<List<String>> versions = glue.getVersions(100);
        assertEquals(200, versions.getCode());
        assertTrue(versions.getContent().isEmpty());
    }

    // ---- 并发 ----

    @Test
    public void 并发保存后版本窗口仍然成立() throws Exception {
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                final int tag = t;
                futures.add(pool.submit(new Runnable() {
                    public void run() {
                        for (int i = 0; i < perThread; i++) {
                            String source = "t" + tag + "-" + i;
                            written.add(source);
                            glue.save(11, source, GlueTypeEnum.GLUE_SHELL.getCode());
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }

            List<String> versions = glue.getVersions(11).getContent();
            assertEquals("保留窗口固定 30", 30, versions.size());
            assertEquals("版本不得重复", 30, new HashSet<String>(versions).size());
            assertEquals("总写入数", threads * perThread, written.size());
            assertTrue("保留下来的都必须是真实写过的源码", written.containsAll(versions));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void 边写边读不得看到撕裂快照() throws Exception {
        final int writes = 400;
        final AtomicInteger tornReads = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        final CountDownLatch start = new CountDownLatch(1);
        int nonEmpty = 0;
        Future<?> writer = null;
        try {
            writer = pool.submit(new Runnable() {
                public void run() {
                    awaitStart(start);
                    for (int i = 0; i < writes; i++) {
                        glue.save(12, "src-" + i, GlueTypeEnum.GLUE_SHELL.getCode());
                    }
                }
            });
            List<Future<Integer>> readers = new ArrayList<Future<Integer>>();
            for (int r = 0; r < 3; r++) {
                readers.add(pool.submit(new Callable<Integer>() {
                    public Integer call() {
                        awaitStart(start);
                        int seen = 0;
                        for (int i = 0; i < 600; i++) {
                            try {
                                List<String> versions = glue.getVersions(12).getContent();
                                if (!versions.isEmpty()) {
                                    seen++;
                                    if (versions.size() > 30) {
                                        tornReads.incrementAndGet();
                                    }
                                }
                            } catch (RuntimeException e) {
                                failures.incrementAndGet();
                            }
                        }
                        return seen;
                    }
                }));
            }
            start.countDown();
            for (Future<Integer> reader : readers) {
                nonEmpty += reader.get(20, TimeUnit.SECONDS);
            }
            writer.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("读侧不得抛异常(原地改共享列表会读到半截数组)", 0, failures.get());
        assertEquals("快照不得超出保留窗口", 0, tornReads.get());
        assertTrue("读线程应确实读到过内容", nonEmpty > 0);
        assertEquals("src-" + (writes - 1), glue.get(12).getContent());
    }

    // ---- 控制器透传 ----

    @Test
    public void 控制器原样透传三个端点() throws Exception {
        GlueController controller = new GlueController();
        Field field = GlueController.class.getDeclaredField("glueService");
        field.setAccessible(true);
        field.set(controller, glue);

        assertEquals(200, controller.save(21, "echo hi", GlueTypeEnum.GLUE_SHELL.getCode()).getCode());
        assertEquals("echo hi", controller.get(21).getContent());
        assertEquals(Collections.singletonList("echo hi"), controller.getVersions(21).getContent());

        assertEquals("校验失败也要如实回传", 500, controller.save(0, "x", GlueTypeEnum.GLUE_SHELL.getCode()).getCode());
        assertEquals(500, controller.get(999).getCode());
    }

    // ---- 辅助 ----

    private final Set<String> written = Collections.synchronizedSet(new HashSet<String>());

    private static void awaitStart(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
