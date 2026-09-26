package com.zifang.z.schedule.core.model;

import com.zifang.z.schedule.core.config.ScheduleProperties;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * JobInfo 模型新增字段的单元测试.
 */
public class JobInfoTest {

    @Test
    public void testNewFieldsGetterSetter() {
        JobInfo job = new JobInfo();

        // triggerType
        job.setTriggerType("FIX_RATE");
        assertEquals("FIX_RATE", job.getTriggerType());

        // fixInterval
        job.setFixInterval(60000L);
        assertEquals(Long.valueOf(60000L), job.getFixInterval());

        // misfireStrategy
        job.setMisfireStrategy("FIRE_ONCE_NOW");
        assertEquals("FIRE_ONCE_NOW", job.getMisfireStrategy());

        // childJobId
        job.setChildJobId("1,2,3");
        assertEquals("1,2,3", job.getChildJobId());
    }

    @Test
    public void testNewFieldsDefaultValues() {
        JobInfo job = new JobInfo();
        // 默认值：triggerType 默认为 CRON（由读侧收敛），fixInterval 默认是"没带这一列"
        assertNull(job.getTriggerType());
        assertNull(job.getFixInterval());
        assertNull(job.getMisfireStrategy());
        assertNull(job.getChildJobId());
    }

    /**
     * 这三列必须是引用类型，否则 {@code /jobinfo/update} 的 partial update 无从判断"补丁带没带这一列"：
     * primitive 下缺席和显式 0 都是 0，而 0 对这三列都是有意义的值（不限超时 / 不重试 / 无间隔）。
     * 装箱前合并闸门只能靠数值猜，代价是要么抹掉列值、要么吞掉合法的显式 0。
     */
    @Test
    public void 补丁字段用null表示缺席而不是0() {
        JobInfo patch = new JobInfo();
        patch.setId(7);
        patch.setJobDesc("只改描述");

        assertNull("缺席的超时不能被读成 0", patch.getExecutorTimeout());
        assertNull("缺席的重试次数不能被读成 0", patch.getExecutorFailRetryCount());
        assertNull("缺席的间隔不能被读成 0", patch.getFixInterval());

        patch.setExecutorTimeout(0);
        patch.setExecutorFailRetryCount(0);
        patch.setFixInterval(0L);
        assertEquals(Integer.valueOf(0), patch.getExecutorTimeout());
        assertEquals(Integer.valueOf(0), patch.getExecutorFailRetryCount());
        assertEquals(Long.valueOf(0L), patch.getFixInterval());
    }

    @Test
    public void testSchedulePropertiesDefaults() {
        ScheduleProperties props = new ScheduleProperties();

        assertEquals("", props.getAccessToken());
        assertEquals(200, props.getTriggerPoolFastMax());
        assertEquals(200, props.getTriggerPoolSlowMax());
        assertEquals(5000L, props.getTriggerPoolSlowThreshold());
        assertEquals(30, props.getLogRetentionDays());
        assertEquals(0, props.getExecutorTimeout());
        assertEquals("zh_CN", props.getI18n());
    }

    @Test
    public void testSchedulePropertiesSetter() {
        ScheduleProperties props = new ScheduleProperties();
        props.setAccessToken("my-secret-token");
        props.setTriggerPoolFastMax(100);
        props.setLogRetentionDays(60);
        props.setI18n("en");

        assertEquals("my-secret-token", props.getAccessToken());
        assertEquals(100, props.getTriggerPoolFastMax());
        assertEquals(60, props.getLogRetentionDays());
        assertEquals("en", props.getI18n());
    }
}