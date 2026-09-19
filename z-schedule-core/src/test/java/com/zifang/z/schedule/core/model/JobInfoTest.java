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
        assertEquals(60000L, job.getFixInterval());

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
        // 默认值：triggerType 默认为 CRON，fixInterval 默认为 0
        assertNull(job.getTriggerType());
        assertEquals(0L, job.getFixInterval());
        assertNull(job.getMisfireStrategy());
        assertNull(job.getChildJobId());
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