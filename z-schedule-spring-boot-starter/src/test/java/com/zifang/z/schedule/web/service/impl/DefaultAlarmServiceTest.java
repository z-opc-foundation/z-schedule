package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * {@link DefaultAlarmService} 的账目诚实性：它不发任何邮件，所以它<b>不许</b>把
 * {@code alarm_status} 写成 2（DDL 注释：0-默认 1-无需告警 <b>2-告警成功</b> 3-告警失败）。
 *
 * <p>曾经这里是 {@code // TODO: 实际发送邮件逻辑} 紧跟 {@code setAlarmStatus(2)}，
 * 于是管理台把一条从未发出的告警显示成"成功"——比不显示更糟，因为它让人以为有人在盯着。
 */
public class DefaultAlarmServiceTest {

    private final DefaultAlarmService service = new DefaultAlarmService();

    @Test
    public void 配了邮箱也只能是告警失败而不是告警成功() {
        JobLog log = new JobLog();
        log.setAlarmStatus(1);

        service.sendAlarm(job("ops@example.com, dev@example.com "), log);

        assertNotEquals("内置实现没有邮件通道，绝不许置 2（告警成功）", 2, log.getAlarmStatus());
        assertEquals("配了邮箱却发不出去 = 一次失败的告警", 3, log.getAlarmStatus());
    }

    @Test
    public void 没配邮箱时不得改动告警状态() {
        // 这一例是上一例的对照：如果把"置 3"挪到方法开头无条件执行，
        // 上一例照样绿，这一例必红——没配邮箱的普通失败不该被记成"告警失败"。
        JobLog log = new JobLog();
        log.setAlarmStatus(1);

        service.sendAlarm(job("   "), log);
        assertEquals(1, log.getAlarmStatus());

        JobLog nullMail = new JobLog();
        nullMail.setAlarmStatus(1);
        service.sendAlarm(job(null), nullMail);
        assertEquals(1, nullMail.getAlarmStatus());
    }

    @Test
    public void 入参缺失不得抛异常() {
        // 执行链路里 sendAlarm 是包在 finishFailure 里调的，抛出来会被 warn 吞掉，
        // 但 null 入参本身就是调用方的 bug，这里只钉"不影响主流程"。
        service.sendAlarm(null, null);
        service.sendAlarm(job("a@b.c"), null);
    }

    private static JobInfo job(String alarmEmail) {
        JobInfo info = new JobInfo();
        info.setId(7);
        info.setAlarmEmail(alarmEmail);
        return info;
    }
}
