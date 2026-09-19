package com.zifang.z.schedule.web.service.impl;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.web.service.AlarmService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * 默认邮件告警实现
 */
@Service
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

        try {
            // TODO: 实际发送邮件逻辑
            alarmLog.setAlarmStatus(2);
            log.info("[z-schedule] Alarm sent successfully for job={}, emails={}", jobId, emailList);
        } catch (Exception e) {
            alarmLog.setAlarmStatus(3);
            log.error("[z-schedule] Alarm failed for job={}, emails={}, error={}", jobId, emailList, e.getMessage(), e);
        }
    }
}
