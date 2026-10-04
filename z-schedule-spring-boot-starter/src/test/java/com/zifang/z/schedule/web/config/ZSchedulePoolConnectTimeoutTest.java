package com.zifang.z.schedule.web.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 调度池<b>物理连接</b>的时间界。
 *
 * <p><b>2026-10-04 实测订正：原注释「{@code maxWait} 管不到新建物理连接那一段」是错的。</b>
 * 原文拿"控制支 1 s 都没抛、它偏不"当证成，实测<b>它就在 1.007 s 抛了</b> ——
 * 控制支反过来证明了被它否掉的那个解释。
 *
 * <p><b>真实结构</b>（Druid 1.2.24 源码与实测两边对上，行号为该版本
 * {@code com/alibaba/druid/pool/DruidDataSource.java}）：
 * <pre>
 *   调用线程 getConnection(maxWait)
 *     └─ getConnectionInternal:  expiredTime = now + maxWait                     :1369-1370
 *          ├─ pollLast(startTime, expiredTime)   在池的 notEmpty 上等到 expiredTime :1475
 *          └─ 到点 holder 仍为 null ⇒ GetConnectionTimeoutException(msg, createError) :1513-1571
 *   后台 CreateConnectionThread（被 empty.signal 唤醒，守护线程）                  :2533-2639
 *     └─ 循环 createPhysicalConnection()
 *          └─ connectTimeout / socketTimeout 作用在<b>每一次尝试</b>上
 *          └─ 失败记进池的 createError；失败后歇 timeBetweenConnectErrorMillis 再试
 * </pre>
 *
 * <p>⇒ 两个旋钮是<b>总 / 单次</b>两层，<b>互不冲突</b>：
 * <ul>
 *   <li><b>maxWait</b>：整段的<b>总</b>界 —— 调用线程等多久就放弃。实测每次都精确贴着它
 *       （1000→1015/1017、20000→20018、30000→30016/30017）。</li>
 *   <li><b>socketTimeout</b>：<b>单次</b>尝试的界。设得比 maxWait 小时那次尝试早失败、
 *       记进 createError，到 maxWait 由 Druid 收尾，于是异常<b>带上</b>驱动的 cause。
 *       它同时还管"借到的老连接上的读包"（§23 线上 75 s 那一档：
 *       {@code testOnBorrow=false} ⇒ 借出瞬间成功、前面没有 maxWait）。</li>
 *   <li><b>connectTimeout</b>：单次尝试里"三次握手都完不成"的形状（SYN 被静默丢弃）。</li>
 * </ul>
 *
 * <p>黑洞形状 = {@link ServerSocket} 绑上后从不 {@code accept}：三次握手由内核完成，
 * 驱动的读永远等不到 MySQL 的第一个包。
 *
 * <p>⚠ 诚实记账：<b>本机造不出的两个形状</b> ——
 * "借到一条已建立但对端变哑的老连接"（要一个会讲 MySQL 协议、能中途变哑的服务端）
 * 与"SYN 被静默丢弃"（要不可路由地址）。
 * 所以 {@code socketTimeout} 在"借到的老连接"那一档、
 * {@code connectTimeout} 在"SYN 被丢弃"那一档，都只钉到"确实递给了驱动"这一层；
 * 它们的<b>计时</b>由 {@link #读超时只管单次尝试而整段的界仍是maxWait()} 那一条间接证明。
 * 完整读数表见 {@code _doc/POOL-TIMEOUT-MEASUREMENT.md}。
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
        m.put("z.base.db.schedule.max-wait", 1000L);
        return m;
    }

    /** 异常链的类名，' &lt;- ' 连接，循环 cause 时截断 —— 只为断言"是谁在定界"服务。 */
    private static String chainOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            sb.append(i == 0 ? "" : " <- ").append(t.getClass().getSimpleName());
            if (t.getCause() == t) {
                break;
            }
        }
        return sb.length() == 0 ? "(没有异常)" : sb.toString();
    }

    /**
     * 默认递下去的<b>就是文档里那两个数</b>。
     *
     * <p>2026-10-04 订正：这一支原先断言 {@code socketTimeout} 默认必须为 null，
     * 报 "expected null, but was:60000"。它与生产代码在<b>同一个</b>提交
     * （4f89d9c，2026-09-27「收口工作树未提交改动」）里进来，自相矛盾：
     * 测例说"不许默认就开，开了会砍掉慢查询"，生产代码的注释则用数据驳回了这个前提
     * —— 250 真机上最慢的合法落库语句是十几到几十毫秒量级（§4.2 那组 17–50 ms），
     * 60 s 是三个数量级的余量；而"不设"的代价是 §23 实测的那一档：
     * {@code /jobinfo/list} 连续 30 s、75 s、90 s 无应答，一个请求线程配一条永久悬住的连接，
     * 来一个悬一对，最后整张管理面跟着死。
     *
     * <p>⇒ 以生产代码为准（它有论证、还留了逃生口），本支改为钉住那个数。
     */
    @Test
    public void 默认递下去的旋钮就是文档里那两个数() {
        Properties p = ((DruidDataSource) poolWith(new HashMap<String, Object>())).getConnectProperties();
        assertNotNull("默认必须给驱动递一个 connectProperties（否则池空时那段是无限等）", p);
        assertEquals("connectTimeout 必须真的递到驱动，否则模块默认值只是一句注释",
                String.valueOf(ZScheduleAutoConfiguration.DEFAULT_SCHEDULE_CONNECT_TIMEOUT_MILLIS),
                p.getProperty("connectTimeout"));
        // 这一条刻意写<b>字面量</b>而不是引用常量：两边都取自同一个常量的话，改了常量两边一起动，
        // 断言恒为真（2026-10-04 变异验证实测：把 60000 改成 30000 照样 6/6 绿）。
        assertEquals("socketTimeout 的默认必须是 60000。这个数有出处、也有运维代价："
                        + "250 真机基线里最慢的合法落库语句是十几到几十毫秒量级（§4.2 那组 17–50 ms），"
                        + "60 s 是三个数量级的余量；而『不设』的代价是 §23 实测的那一档 —— "
                        + "/jobinfo/list 连续 30 s、75 s、90 s 无应答，一个请求线程配一条永久悬住的连接，"
                        + "来一个悬一对，最后整张管理面跟着死。改它要连同这两处证据一起重算",
                60000, ZScheduleAutoConfiguration.DEFAULT_SCHEDULE_SOCKET_TIMEOUT_MILLIS);
        assertEquals("60 s 也必须真的递到驱动，否则上一条钉的只是一个孤零零的常量",
                String.valueOf(ZScheduleAutoConfiguration.DEFAULT_SCHEDULE_SOCKET_TIMEOUT_MILLIS),
                p.getProperty("socketTimeout"));
    }

    /** 上面那个"嫌长就调小"的反面：{@code 0} 必须真能把旋钮摘掉，否则逃生口只是文档上的一句话。 */
    @Test
    public void 设零就摘掉读超时这个逃生口() {
        assertNull("显式设 0 应当把 socketTimeout 整个摘掉（文档承诺的『退回旧行为』）",
                ((DruidDataSource) poolWith(
                        map("z.base.db.schedule.socket-timeout-millis", 0)))
                        .getConnectProperties().getProperty("socketTimeout"));
        assertEquals("同一张卡上 connectTimeout 不受 0 影响，两个旋钮各管各的",
                String.valueOf(ZScheduleAutoConfiguration.DEFAULT_SCHEDULE_CONNECT_TIMEOUT_MILLIS),
                ((DruidDataSource) poolWith(
                        map("z.base.db.schedule.socket-timeout-millis", 0)))
                        .getConnectProperties().getProperty("connectTimeout"));
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
     * 池空要新建物理连接时，<b>整段的界就是 {@code maxWait}</b>（2026-10-04 实测订正）。
     *
     * <p>原文这一支叫「不设读超时时哪怕 maxWait 一秒也会一直卡住」，并拿它当控制支
     * 来证成"maxWait 管不到建连"。**实测它 1.007 s 就抛了** ——
     * 恰恰是被否掉的那个解释。
     *
     * <p>本支不设 {@code socketTimeout}（设 0 ⇒ 驱动不管读包），只量总界，
     * 而且用<b>配对</b>断言而不是单点读数：maxWait 压到 1 s ⇒ 约 1 s 抛；
     * 再把 maxWait 抬到 3 s ⇒ 约 3 s 抛。<b>耗时跟着 maxWait 走</b>，
     * 这才把"maxWait 定界"钉死（原来只钉了 1 s 这一个点，换个 maxWait 就漂）。
     */
    @Test
    public void 建连那一段的界是maxWait而不是驱动超时() throws Exception {
        ServerSocket ss = blackHole();
        try {
            Map<String, Object> fast = toBlackHole(ss);
            long t0 = System.currentTimeMillis();
            try {
                poolWith(fast).getConnection().close();
                fail("黑洞里不该拿得到连接");
            } catch (Exception ignore) {
            }
            long fastTook = System.currentTimeMillis() - t0;
            assertTrue("maxWait=1000 应当约 1 s 抛，实测 " + fastTook + " ms", fastTook < 3000);
            // 下限：排除"端口被拒所以秒回"这种与超时无关的假通过
            assertTrue("实测 " + fastTook + " ms，快得像端口被拒（Connection refused），"
                    + "那不是超时在起作用", fastTook >= 700);

            // 变一下 maxWait，耗时必须跟着走 —— 否则"贴着 maxWait"只是巧合
            Map<String, Object> slow = toBlackHole(ss);
            slow.put("z.base.db.schedule.max-wait", 3000L);
            t0 = System.currentTimeMillis();
            try {
                poolWith(slow).getConnection().close();
                fail("黑洞里不该拿得到连接");
            } catch (Exception ignore) {
            }
            long slowTook = System.currentTimeMillis() - t0;
            assertTrue("maxWait 抬到 3000 后耗时应跟着涨（实测 " + fastTook + " → " + slowTook
                    + " ms）。若两者相近，说明根本不是 maxWait 在定界，这条断言要重做",
                    slowTook > fastTook + 1000);
        } finally {
            try {
                ss.close();
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * {@code socketTimeout} 只管<b>单次</b>物理连接尝试，<b>整段的总界仍是 maxWait</b>。
     *
     * <p>形状刻意把两个数<b>拉开</b>（maxWait=3000 / socketTimeout=200），
     * 这样"谁在定界"没法靠时间巧合蒙对：
     * <ul>
     *   <li>若真是读超时在定界 ⇒ 约 200 ms 抛 {@code CommunicationsException}；</li>
     *   <li>若根本没人定界 ⇒ 要么秒回，要么一直卡着。</li>
     * </ul>
     * 实测：<b>3006 ms</b>，最外层是 Druid 自己的 {@code GetConnectionTimeoutException}，
     * 它的 cause 链是 {@code SocketTimeoutException ← CJCommunicationsException
     * ← CommunicationsException}。即：驱动确实按 200 ms 失败了，
     * 只是调用线程仍在等到 maxWait 才收尾，并把那次失败当 cause 附上。
     *
     * <p>两个时间断言都留了 1000 ms 以上余量，不贴边（真读数 3006，窗口 [2000, 8000]）。
     *
     * <p>反向可证：把 {@code applyDriverConnectTimeouts} 里的 {@code socketTimeout}
     * 接线去掉再跑本支，cause 链上就不会再有 {@code SocketTimeoutException}，本支报红。
     */
    @Test
    public void 读超时只管单次尝试而整段的界仍是maxWait() throws Exception {
        ServerSocket ss = blackHole();
        try {
            Map<String, Object> props = toBlackHole(ss);
            props.put("z.base.db.schedule.max-wait", 3000L);
            props.put("z.base.db.schedule.socket-timeout-millis", 200);
            long t0 = System.currentTimeMillis();
            Throwable thrown = null;
            try {
                poolWith(props).getConnection().close();
                fail("黑洞里不该拿得到连接");
            } catch (Throwable e) {
                thrown = e;
            }
            long took = System.currentTimeMillis() - t0;
            String chain = chainOf(thrown);

            assertTrue("整段应由 maxWait(3000) 定界，实测 " + took + " ms。"
                    + "若约 200 ms 就回来说明是读超时在定界（那正是原注释的立论）",
                    took >= 2000);
            assertTrue("实测 " + took + " ms，远超 maxWait(3000)+余量，说明这一段没人给它界", took < 8000);
            assertTrue("到期者必须是 Druid 自己（maxWait 到点），实测链：" + chain,
                    chain.startsWith("GetConnectionTimeoutException"));
            assertTrue("socketTimeout(200) 应当已生效：那次尝试的失败应当作为 cause 附在链上，"
                    + "实测链：" + chain + "。若链上只有 GetConnectionTimeoutException，"
                    + "说明 socketTimeout 没递到驱动（旋钮没接线）", chain.contains("SocketTimeoutException"));
            System.out.println("[p47] 黑洞 + maxWait=3000 + socketTimeout=200 → " + took + " ms, " + chain);
        } finally {
            try {
                ss.close();
            } catch (Exception ignore) {
            }
        }
    }
}
