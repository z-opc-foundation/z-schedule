package com.zifang.z.schedule.web.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 调度池<b>物理连接</b>的时间界。
 *
 * <p>要钉住的机制不是"配了个参数"，而是"<b>{@code maxWait} 管不到新建物理连接那一段</b>"：
 * 池空时 Druid 在调用线程里同步 connect，那一段既不受 {@code maxWait} 约束，也没人给它设驱动超时。
 * 所以两支计时测试都把 {@code max-wait} 压到 1000 ms —— 如果 {@code maxWait} 真能定界，
 * 控制支会在 1 s 就抛；它偏不，一直卡到我们把 {@code socketTimeout} 设进去为止。
 * 这两支互为对照，缺任何一支都分不开"旋钮生效"和"其实是 maxWait 放手"。
 *
 * <p>形状是"接得下 TCP 但一个字节都不回"（{@link ServerSocket} 绑上后从不 {@code accept}，
 * 握手由内核完成，驱动的读就永远等不到 MySQL 的第一个包）。它量的正是线上那一档
 * （{@code _doc/003_script/e2e/README.md} §23：{@code /actuator/health} 75 s 无应答）。
 *
 * <p>⚠ 本机量不到的是<b>另一</b>形状："SYN 被静默丢弃"要靠一个不可路由的地址，那在 CI / 笔记本
 * （VPN、代理、fake-ip）上是随机的，所以 {@code connectTimeout} 只钉到"确实递给了驱动"这一层
 * （{@link #默认只定界新建连接的时间不砍正在跑的查询()}），它的计时不在这里量。
 */
public class ZSchedulePoolConnectTimeoutTest {

    private static StandardEnvironment envWith(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        if (!props.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("host", props));
        }
        return env;
    }

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    private static DataSource poolWith(Map<String, Object> props) {
        return new ZScheduleAutoConfiguration().dataSourceSchedule(envWith(props));
    }

    /** 一个绑上但从不 accept 的端口：connect 成功，读永远等不到包。 */
    private static ServerSocket blackHole() throws Exception {
        return new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    private static Map<String, Object> toBlackHole(ServerSocket ss) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("z.base.db.schedule.host", "127.0.0.1");
        m.put("z.base.db.schedule.port", ss.getLocalPort());
        m.put("z.base.db.schedule.database", "z_schedule_none");
        // 池自己那三个数都要压小：initialSize>0 会让 init() 连着建 5 条，计时就被摊成 5 倍
        m.put("z.base.db.schedule.initial-size", 0);
        m.put("z.base.db.schedule.min-idle", 0);
        // maxWait 故意压到 1 s：它是本节的"被否掉的那个解释"，见类注释
        m.put("z.base.db.schedule.max-wait", 1000L);
        return m;
    }

    /** 在别的线程里跑一次 getConnection()，返回"到点还卡着吗"；无论与否都把黑洞放开，不留僵尸线程。 */
    private static boolean stillBlockedAt(DataSource ds, ServerSocket ss, long millis) throws Exception {
        final boolean[] done = new boolean[]{false};
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Connection c = ds.getConnection();
                    if (c != null) {
                        c.close();
                    }
                } catch (Throwable ignore) {
                    // 这一支只量"有没有在 millis 内收尾"，抛的是什么由调用方的断言管
                } finally {
                    done[0] = true;
                }
            }
        }, "p47-getconnection");
        t.setDaemon(true);
        t.start();
        t.join(millis);
        boolean blocked = !done[0];
        // accept 之后立刻 close ⇒ 驱动的读收到 FIN，卡住的线程能自己退出去
        Socket peer = null;
        try {
            peer = ss.accept();
        } catch (Exception ignore) {
        } finally {
            if (peer != null) {
                try {
                    peer.close();
                } catch (Exception ignore) {
                }
            }
        }
        t.join(8000);
        assertFalse("放开黑洞后 getConnection 线程仍没收尾（测例会拖到 fork 退出）", t.isAlive());
        return blocked;
    }

    @Test
    public void 默认只定界新建连接的时间不砍正在跑的查询() {
        Properties p = ((DruidDataSource) poolWith(new HashMap<String, Object>())).getConnectProperties();
        assertNotNull("默认必须给驱动递一个 connectProperties（否则池空时那段是无限等）", p);
        assertEquals(String.valueOf(ZScheduleAutoConfiguration.DEFAULT_SCHEDULE_CONNECT_TIMEOUT_MILLIS),
                p.getProperty("connectTimeout"));
        assertNull("socketTimeout 不许默认就开：它是每个读包的超时，开了会掐掉慢查询",
                p.getProperty("socketTimeout"));
    }

    /** 阳性对照：默认值不许压过宿主，两个旋钮都得听显式设的那个。 */
    @Test
    public void 宿主显式设值时两个旋钮都让位() {
        Map<String, Object> props = map("z.base.db.schedule.connect-timeout-millis", 1234);
        props.put("z.base.db.schedule.socket-timeout-millis", 5678);
        Properties p = ((DruidDataSource) poolWith(props)).getConnectProperties();
        assertEquals("宿主写了 1234 就得是 1234", "1234", p.getProperty("connectTimeout"));
        assertEquals("socketTimeout 显式设 5678 就要递进去", "5678", p.getProperty("socketTimeout"));
    }

    /** 与池参数同一套优先级链：模块键 > 全局兜底键 > 本模块默认。 */
    @Test
    public void 全局兜底键生效而模块键压过它() {
        Properties onlyGlobal = ((DruidDataSource) poolWith(
                map("z.base.db.default.connect-timeout-millis", 4321))).getConnectProperties();
        assertEquals("只设全局兜底时它应当生效", "4321", onlyGlobal.getProperty("connectTimeout"));

        Map<String, Object> both = map("z.base.db.default.connect-timeout-millis", 4321);
        both.put("z.base.db.schedule.connect-timeout-millis", 2222);
        assertEquals("模块键必须赢过全局兜底（否则一个模块改的是全集群）", "2222",
                ((DruidDataSource) poolWith(both)).getConnectProperties().getProperty("connectTimeout"));
    }

    /**
     * 控制支：<b>不设</b> {@code socketTimeout} 时，读永远等不到包 —— 而 {@code maxWait} 已经
     * 被压到 1 s，它拦不住。这一支量的是"线上 health 挂 75 s"那个形状本身。
     */
    @Test
    public void 不设读超时时哪怕maxWait一秒也会一直卡住() throws Exception {
        ServerSocket ss = blackHole();
        try {
            Map<String, Object> props = toBlackHole(ss);
            // 只把读超时拿掉：connectTimeout 默认 5000 管不到这一段（内核已替服务端完成握手）
            DataSource ds = poolWith(props);
            assertTrue("默认（socketTimeout 关）下 getConnection 在 4 s 时仍卡着——这才是 §23 那一档",
                    stillBlockedAt(ds, ss, 4000));
        } finally {
            try {
                ss.close();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 同一份黑洞、同一个 1 s 的 {@code maxWait}，只多设一个 {@code socketTimeout=1500}：
     * 必须<b>有界</b>地失败。耗时落在 1500 ms 附近而远大于 1 s ⇒ 放手的是读超时，不是 maxWait。
     */
    @Test
    public void 设了读超时就要在有界时间内报错() throws Exception {
        ServerSocket ss = blackHole();
        try {
            Map<String, Object> props = toBlackHole(ss);
            props.put("z.base.db.schedule.socket-timeout-millis", 1500);
            final DataSource ds = poolWith(props);
            long t0 = System.currentTimeMillis();
            String msg = null;
            try {
                Connection c = ds.getConnection();
                c.close();
                fail("黑洞里不该拿得到连接");
            } catch (Throwable e) {
                msg = String.valueOf(e);
            }
            long took = System.currentTimeMillis() - t0;
            assertTrue("应当在 socketTimeout(1500) 附近抛，实测 " + took + " ms（下限挡掉"
                    + "\"其实是端口拒绝所以秒回\"这种假通过）", took >= 1200);
            assertTrue("实测 " + took + " ms，远大于 maxWait(1000) ⇒ 定界的是读超时而不是 maxWait",
                    took <= 12000);
            assertNotNull(msg);
            System.out.println("[p47] 黑洞 + socketTimeout=1500 → " + took + " ms, " + msg);
        } finally {
            try {
                ss.close();
            } catch (Exception ignore) {
            }
        }
    }
}
