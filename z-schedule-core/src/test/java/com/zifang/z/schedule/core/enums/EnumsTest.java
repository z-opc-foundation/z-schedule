package com.zifang.z.schedule.core.enums;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 新增/修改的调度枚举的单元测试.
 */
public class EnumsTest {

    @Test
    public void testTriggerTypeEnumIncludesFixDelay() {
        // 验证 FIX_DELAY 已加入枚举
        TriggerTypeEnum fixDelay = TriggerTypeEnum.FIX_DELAY;
        assertNotNull(fixDelay);
        assertEquals("FIX_DELAY", fixDelay.getCode());

        // match 方法能正确查找
        assertEquals(TriggerTypeEnum.CRON, TriggerTypeEnum.match("CRON"));
        assertEquals(TriggerTypeEnum.FIX_RATE, TriggerTypeEnum.match("FIX_RATE"));
        assertEquals(TriggerTypeEnum.FIX_DELAY, TriggerTypeEnum.match("FIX_DELAY"));
        assertEquals(TriggerTypeEnum.MANUAL, TriggerTypeEnum.match("MANUAL"));
        assertEquals(TriggerTypeEnum.RETRY, TriggerTypeEnum.match("RETRY"));
        assertEquals(TriggerTypeEnum.PARENT, TriggerTypeEnum.match("PARENT"));
        assertEquals(TriggerTypeEnum.API, TriggerTypeEnum.match("API"));

        // 不存在的 code 返回 null
        assertNull(TriggerTypeEnum.match("UNKNOWN"));
    }

    @Test
    public void testMisfireStrategyEnum() {
        // 验证两个策略都存在
        MisfireStrategyEnum doNothing = MisfireStrategyEnum.DO_NOTHING;
        MisfireStrategyEnum fireNow = MisfireStrategyEnum.FIRE_ONCE_NOW;
        assertNotNull(doNothing);
        assertNotNull(fireNow);
        assertEquals("DO_NOTHING", doNothing.getCode());
        assertEquals("FIRE_ONCE_NOW", fireNow.getCode());

        // match 方法能正确查找
        assertEquals(MisfireStrategyEnum.DO_NOTHING, MisfireStrategyEnum.match("DO_NOTHING"));
        assertEquals(MisfireStrategyEnum.FIRE_ONCE_NOW, MisfireStrategyEnum.match("FIRE_ONCE_NOW"));
        assertNull(MisfireStrategyEnum.match("UNKNOWN"));
    }

    @Test
    public void testGlueTypeEnum() {
        // 验证 4 种 GLUE 类型都存在
        GlueTypeEnum[] types = GlueTypeEnum.values();
        assertEquals(4, types.length);

        assertEquals("BEAN", GlueTypeEnum.BEAN.getCode());
        assertEquals("GLUE(Java)", GlueTypeEnum.GLUE_JAVA.getCode());
        assertEquals("GLUE(Shell)", GlueTypeEnum.GLUE_SHELL.getCode());
        assertEquals("GLUE(Python)", GlueTypeEnum.GLUE_PYTHON.getCode());

        // match 方法
        assertEquals(GlueTypeEnum.BEAN, GlueTypeEnum.match("BEAN"));
        assertEquals(GlueTypeEnum.GLUE_JAVA, GlueTypeEnum.match("GLUE(Java)"));
        assertEquals(GlueTypeEnum.GLUE_SHELL, GlueTypeEnum.match("GLUE(Shell)"));
        assertEquals(GlueTypeEnum.GLUE_PYTHON, GlueTypeEnum.match("GLUE(Python)"));
        assertNull(GlueTypeEnum.match("UNKNOWN"));
    }

    @Test
    public void testExecutorRouteStrategyEnumAlignedWithXxlJob() {
        // 验证 10 种路由策略都存在（与 XXL-Job 一致）
        ExecutorRouteStrategyEnum[] strategies = ExecutorRouteStrategyEnum.values();
        assertEquals(10, strategies.length);

        // 验证关键路由都在
        assertNotNull(ExecutorRouteStrategyEnum.ROUND);
        assertNotNull(ExecutorRouteStrategyEnum.RANDOM);
        assertNotNull(ExecutorRouteStrategyEnum.CONSISTENT_HASH);
        assertNotNull(ExecutorRouteStrategyEnum.LRU);
        assertNotNull(ExecutorRouteStrategyEnum.LFU);
        assertNotNull(ExecutorRouteStrategyEnum.FAILOVER);
        assertNotNull(ExecutorRouteStrategyEnum.BUSYOVER);
        assertNotNull(ExecutorRouteStrategyEnum.SHARDING_BROADCAST);
        assertNotNull(ExecutorRouteStrategyEnum.FIRST);
        assertNotNull(ExecutorRouteStrategyEnum.LAST);

        // match 方法
        assertEquals(ExecutorRouteStrategyEnum.FIRST, ExecutorRouteStrategyEnum.match("FIRST"));
        assertEquals(ExecutorRouteStrategyEnum.LAST, ExecutorRouteStrategyEnum.match("LAST"));
        assertEquals(ExecutorRouteStrategyEnum.LFU, ExecutorRouteStrategyEnum.match("LFU"));
        assertEquals(ExecutorRouteStrategyEnum.BUSYOVER, ExecutorRouteStrategyEnum.match("BUSYOVER"));
        assertEquals(ExecutorRouteStrategyEnum.LRU, ExecutorRouteStrategyEnum.match("LRU"));
    }

    @Test
    public void testExecutorBlockStrategyEnum() {
        // 验证三种阻塞策略
        assertEquals(3, ExecutorBlockStrategyEnum.values().length);
        assertNotNull(ExecutorBlockStrategyEnum.SERIAL_EXECUTION);
        assertNotNull(ExecutorBlockStrategyEnum.DISCARD_LATER);
        assertNotNull(ExecutorBlockStrategyEnum.COVER_EARLY);

        assertEquals(ExecutorBlockStrategyEnum.SERIAL_EXECUTION,
                ExecutorBlockStrategyEnum.match("SERIAL_EXECUTION"));
        assertEquals(ExecutorBlockStrategyEnum.DISCARD_LATER,
                ExecutorBlockStrategyEnum.match("DISCARD_LATER"));
        assertEquals(ExecutorBlockStrategyEnum.COVER_EARLY,
                ExecutorBlockStrategyEnum.match("COVER_EARLY"));
    }
}