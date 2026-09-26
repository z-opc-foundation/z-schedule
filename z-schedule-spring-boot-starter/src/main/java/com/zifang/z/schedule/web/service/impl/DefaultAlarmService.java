package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.web.service.AlarmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 内置的兜底 {@link AlarmService}：<b>只记账，不发任何东西</b>。
 *
 * <p>{@code alarm_email} 留空 ⇒ 原样返回，日志保持"无需告警"(1)。
 * <p>{@code alarm_email} 配了 ⇒ 置 3（告警失败）并 warn 一行：内置实现没有邮件通道，
 * 配了邮箱也不会收到信。曾经这里置的是 2（告警成功），属于对运维的谎报。
 *
 * <p><b>故意不带 {@code @Service}</b>：这个类是"宿主没提供实现时才有"的兜底，
 * 由 {@code ZScheduleAutoConfiguration.AlarmServiceConfiguration} 以
 * {@code @Bean @ConditionalOnMissingBean(AlarmService.class)} 的形式注册。
 * 带上 {@code @Service} 就会被 {@code @ComponentScan} 抢先注册成固定 bean，
 * 宿主再注册自己的实现就变成两个同类型候选，且条件注解也救不回来。
 */
public class DefaultAlarmService implements AlarmService {

    private static final Logger log = LogManager.getLogger(DefaultAlarmService.class);

    @Override
    public void sendAlarm(JobInfo job, JobLog alarmLog) {
        if (job == null || alarmLog == null) {
            return;
        }

        String alarmEmail = job.getAlarmEmail();
        int jobId = job.getId();

        // 解析告警邮箱（逗号分隔）
        String emails = alarmEmail != null ? alarmEmail.trim() : "";
        if (emails.isEmpty()) {
            log.warn("[z-schedule] No alarm email configured for job={}", jobId);
            return;
        }

        String[] emailArray = emails.split(",");
        StringBuilder emailList = new StringBuilder();
        for (int i = 0; i < emailArray.length; i++) {
            String email = emailArray[i].trim();
            if (!email.isEmpty()) {
                if (emailList.length() > 0) {
                    emailList.append(", ");
                }
                emailList.append(email);
            }
        }

        if (emailList.length() == 0) {
            log.warn("[z-schedule] No valid alarm email found for job={}", jobId);
            return;
        }

        log.info("[z-schedule] Alarm triggered for job={}, emails={}", jobId, emailList);

        // 本类没有任何邮件通道：历史上这里是一句 TODO 却把 alarm_status 置成 2（DDL 注释里
        // 2 = 告警成功），于是管理台会把一条从未发出的告警显示成"成功"。改成 3（告警失败）,
        // 因为站在运维的视角，"配了邮箱却没人发"就是一次失败的告警。
        // 注意：本类是裸 @Service 由组件扫描装配的，自动装配里没有 @ConditionalOnMissingBean，
        // 所以宿主自己再注册一个 AlarmService 并不会"覆盖"它——两个同类型候选会让
        // JobTriggerServiceImpl 的按类型注入直接启动失败。想真接邮件得先补那个扩展点。
        alarmLog.setAlarmStatus(3);
        log.warn("[z-schedule] job={} 配置了告警邮箱 {}，但内置实现未接入邮件通道，"
                + "告警未发出（alarm_status=3 告警失败）", jobId, emailList);
    }
}
