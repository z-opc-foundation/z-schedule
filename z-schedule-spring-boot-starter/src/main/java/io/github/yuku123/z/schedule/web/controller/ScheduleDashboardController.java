package io.github.yuku123.z.schedule.web.controller;

import io.github.yuku123.z.schedule.core.model.JobInfo;
import io.github.yuku123.z.schedule.core.model.JobLog;
import io.github.yuku123.z.schedule.core.model.ReturnT;
import io.github.yuku123.z.schedule.web.service.ExecutorRegistryService;
import io.github.yuku123.z.schedule.web.service.JobGroupService;
import io.github.yuku123.z.schedule.web.service.JobInfoService;
import io.github.yuku123.z.schedule.web.service.JobLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 调度中心 Dashboard 控制器,汇总任务统计信息供前端总览展示.
 * <p>
 * API 基础路径: /dashboard (同时保留 /api/schedule 别名以兼容老前端)
 * 所属模块: z-schedule-admin
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET /dashboard                   — 总览统计(含 stats/recentJobs,前端主入口)</li>
 *   <li>GET /dashboard/stats             — 任务/执行器统计(前端 DashboardStats)</li>
 *   <li>GET /dashboard/scheduleRecords   — 最近调度记录(ScheduleRecord[])</li>
 *   <li>GET /dashboard/executorLoad      — 执行器负载</li>
 *   <li>GET /dashboard/successRateTrend  — 成功率趋势</li>
 * </ul>
 */
@RestController
@RequestMapping({"/dashboard", "/api/schedule"})
public class ScheduleDashboardController {

    @Autowired
    private JobInfoService jobInfoService;

    @Autowired
    private JobLogService jobLogService;

    @Autowired
    private ExecutorRegistryService registryService;

    @Autowired
    private JobGroupService jobGroupService;

    /**
     * 总览统计(向后兼容,返回 data+recentJobs 结构).
     */
    @GetMapping("")
    public Map<String, Object> dashboard() {
        Map<String, Object> result = new HashMap<>();
        try {
            List<JobInfo> all = jobInfoService.getAll();
            int total = all == null ? 0 : all.size();
            long running = all == null ? 0 : all.stream().filter(j -> j.getTriggerStatus() == 1).count();
            long stopped = total - running;

            Map<Integer, Long> groupCounts = all == null ? new HashMap<>()
                    : all.stream().collect(Collectors.groupingBy(JobInfo::getJobGroup, Collectors.counting()));

            List<JobInfo> recent = new ArrayList<>(all == null ? new ArrayList<JobInfo>() : all);
            recent.sort((a, b) -> {
                if (a.getUpdateTime() == null && b.getUpdateTime() == null) return 0;
                if (a.getUpdateTime() == null) return 1;
                if (b.getUpdateTime() == null) return -1;
                return b.getUpdateTime().compareTo(a.getUpdateTime());
            });
            if (recent.size() > 5) recent = recent.subList(0, 5);

            Map<String, Object> summary = new HashMap<>();
            summary.put("totalJobs", total);
            summary.put("runningJobs", running);
            summary.put("stoppedJobs", stopped);
            summary.put("groupCount", groupCounts.size());

            result.put("success", true);
            result.put("data", summary);
            result.put("recentJobs", recent);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * 任务/执行器统计(直接返回 stats 字段,前端 dashboardApi.getStats 直接消费).
     */
    @GetMapping("/stats")
    public ReturnT<Map<String, Object>> stats() {
        List<JobInfo> all = jobInfoService.getAll();
        int total = all == null ? 0 : all.size();
        long running = all == null ? 0 : all.stream().filter(j -> j.getTriggerStatus() == 1).count();

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date todayStart = cal.getTime();

        List<JobLog> todayLogs = jobLogService.query(0, 0, -1, 0).stream()
                .filter(l -> l.getTriggerTime() != null && !l.getTriggerTime().before(todayStart))
                .collect(Collectors.toList());
        int todayTotal = todayLogs.size();
        long todaySuccess = todayLogs.stream().filter(l -> l.getHandleCode() == ReturnT.SUCCESS_CODE).count();
        int successRate = todayTotal == 0 ? 0 : (int) Math.round(todaySuccess * 100.0 / todayTotal);
        int failRate = 100 - successRate;

        Map<String, Object> stats = new HashMap<>();
        stats.put("jobCount", total);
        stats.put("runningJobCount", running);
        stats.put("executorCount", registryService.onlineGroupCount());
        stats.put("todayTriggerCount", todayTotal);
        stats.put("successRate", successRate);
        stats.put("failRate", failRate);
        return ReturnT.success(stats);
    }

    /**
     * 最近调度记录(前端期望 ScheduleRecord[]).
     */
    @GetMapping("/scheduleRecords")
    public ReturnT<List<Map<String, Object>>> scheduleRecords(@RequestParam(required = false, defaultValue = "20") int limit) {
        List<JobLog> logs = jobLogService.query(0, 0, -1, limit);
        Map<Integer, JobInfo> infoMap = new HashMap<>();
        for (JobInfo info : jobInfoService.getAll()) infoMap.put(info.getId(), info);

        List<Map<String, Object>> records = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (JobLog l : logs) {
            Map<String, Object> r = new HashMap<>();
            r.put("time", l.getTriggerTime() == null ? null : sdf.format(l.getTriggerTime()));
            r.put("jobId", l.getJobId());
            JobInfo info = infoMap.get(l.getJobId());
            r.put("jobDesc", info == null ? ("#" + l.getJobId()) : info.getJobDesc());
            int code = l.getHandleCode();
            String status;
            if (code == ReturnT.SUCCESS_CODE) status = "success";
            else if (code == 0) status = "running";
            else status = "fail";
            r.put("status", status);
            if (l.getHandleTime() != null && l.getTriggerTime() != null) {
                r.put("duration", l.getHandleTime().getTime() - l.getTriggerTime().getTime());
            }
            r.put("message", l.getHandleMsg());
            records.add(r);
        }
        return ReturnT.success(records);
    }

    /**
     * 执行器负载列表(前端期望 {appName, instanceCount, runningTasks}[]).
     */
    @GetMapping("/executorLoad")
    public ReturnT<List<Map<String, Object>>> executorLoad() {
        List<Map<String, Object>> list = registryService.loadAll();
        for (Map<String, Object> m : list) {
            String appName = (String) m.get("appName");
            if (m.get("instanceCount") == null || ((Number) m.get("instanceCount")).intValue() == 0) {
                List<String> nodes = jobGroupService.getRegistryNodes(appName);
                m.put("instanceCount", nodes.size());
            }
        }
        return ReturnT.success(list);
    }

    /**
     * 成功率趋势(近 N 天).
     */
    @GetMapping("/successRateTrend")
    public ReturnT<List<Map<String, Object>>> successRateTrend(@RequestParam(required = false, defaultValue = "7") int days) {
        List<Map<String, Object>> result = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd");
        List<JobLog> all = jobLogService.query(0, 0, -1, 0);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 0);

        for (int i = days - 1; i >= 0; i--) {
            Calendar day = (Calendar) cal.clone();
            day.add(Calendar.DAY_OF_YEAR, -i);
            Date dayEnd = day.getTime();
            Calendar dayStart = (Calendar) day.clone();
            dayStart.set(Calendar.HOUR_OF_DAY, 0);
            dayStart.set(Calendar.MINUTE, 0);
            dayStart.set(Calendar.SECOND, 0);
            Date dayStartDate = dayStart.getTime();

            int total = 0, success = 0;
            for (JobLog l : all) {
                if (l.getTriggerTime() == null) continue;
                Date t = l.getTriggerTime();
                if (t.before(dayStartDate) || t.after(dayEnd)) continue;
                total++;
                if (l.getHandleCode() == ReturnT.SUCCESS_CODE) success++;
            }
            int rate = total == 0 ? 100 : (int) Math.round(success * 100.0 / total);
            Map<String, Object> m = new HashMap<>();
            m.put("date", sdf.format(dayStartDate));
            m.put("successRate", rate);
            m.put("total", total);
            result.add(m);
        }
        return ReturnT.success(result);
    }
}