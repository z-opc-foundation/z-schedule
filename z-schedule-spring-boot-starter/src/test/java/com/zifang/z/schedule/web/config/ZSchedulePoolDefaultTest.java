package com.zifang.z.schedule.web.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 调度池默认并发的<b>兑现层</b>测试。
 *
 * <p>为什么不看 yml 那一行字：250 真机上一度存在两个 {@code max-active}——
 * {@code spring.datasource.druid.*}（actuator 健康检查用的那个，引擎从不借它）和
 * {@code z.base.db.schedule.*}（引擎真正用的那个）。改前者的时候吞吐一秒没动，
 * 而配置文件里那行字看着像是"已经配了池"。所以这里断言的是<b>真建出来的 DruidDataSource
 * 上的 getMaxActive()</b>，不是任何属性字符串。
 *
 * <p>默认值住在 starter 而不是 z-boot 的 {@code ModuleDataSourceTemplate}：那个 20 是
 * 所有模块（z-lc / z-kb / …）共用的兜底，z-schedule 改它等于替全集群抬池。
 */
public class ZSchedulePoolDefaultTest {

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

    private static int maxActiveOf(DataSource ds) {
        assertTrue("starter 建的必须是 DruidDataSource，否则下面读到的 maxActive 没有意义: "
                + ds.getClass().getName(), ds instanceof DruidDataSource);
        return ((DruidDataSource) ds).getMaxActive();
    }

    @Test
    public void 宿主什么都没配时调度池默认抬到40() {
        DataSource ds = new ZScheduleAutoConfiguration()
                .dataSourceSchedule(envWith(new HashMap<String, Object>()));
        assertEquals(ZScheduleAutoConfiguration.DEFAULT_SCHEDULE_MAX_ACTIVE, maxActiveOf(ds));
    }

    /** 阳性对照：默认值必须是"压不过宿主"的，否则这条默认就是在覆盖别人的显式决定。 */
    @Test
    public void 宿主显式设值时默认必须让位() {
        DataSource ds = new ZScheduleAutoConfiguration()
                .dataSourceSchedule(envWith(map("z.base.db.schedule.max-active", 7)));
        assertEquals("宿主写了 7 就得是 7（默认值不许覆盖显式配置）", 7, maxActiveOf(ds));
    }

    /**
     * 另一个方向的对照：宿主只设<b>全局</b>兜底 {@code z.base.db.default.max-active} 时，
     * 本模块那条<b>模块级</b>默认仍然生效。这是刻意的取舍——"全局默认"是给没表达过偏好的
     * 模块准备的，而 z-schedule 按 250 实测数据表达了偏好。
     */
    @Test
    public void 模块级默认压过宿主的全局兜底() {
        DataSource ds = new ZScheduleAutoConfiguration()
                .dataSourceSchedule(envWith(map("z.base.db.default.max-active", 9)));
        assertEquals(ZScheduleAutoConfiguration.DEFAULT_SCHEDULE_MAX_ACTIVE, maxActiveOf(ds));
    }

    /** 属性源要按名字挡住重复注入，否则同一个 key 会被后写的值改掉、且排查时看不见有几个来源。 */
    @Test
    public void 重复注入不叠层() {
        StandardEnvironment env = envWith(new HashMap<String, Object>());
        ZScheduleAutoConfiguration.applySchedulePoolDefaults(env);
        org.springframework.core.env.PropertySource<?> first =
                env.getPropertySources().get(ZScheduleAutoConfiguration.SCHEDULE_DEFAULT_SOURCE);
        ZScheduleAutoConfiguration.applySchedulePoolDefaults(env);
        assertSame("第二次调用必须原样复用同一个属性源", first,
                env.getPropertySources().get(ZScheduleAutoConfiguration.SCHEDULE_DEFAULT_SOURCE));
        // 必须是**最后一个**属性源：不在末尾就意味着它会压住宿主的显式配置
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (org.springframework.core.env.PropertySource<?> ps : env.getPropertySources()) {
            names.add(ps.getName());
        }
        assertEquals("默认属性源必须排在最末尾，实际顺序=" + names,
                ZScheduleAutoConfiguration.SCHEDULE_DEFAULT_SOURCE, names.get(names.size() - 1));
    }
}
