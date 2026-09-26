package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.KillParam;
import com.zifang.z.schedule.core.param.TriggerParam;
import com.zifang.z.schedule.web.service.ExecutorRegistryService;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobLogService;
import com.zifang.z.schedule.web.service.JobTriggerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行器回调 API(供业务侧 z-schedule-executor 调用).
 * <p>
 * API 基础路径: /executor
 * 所属模块: z-schedule-admin
 *
 * <p>主要端点:
 * <ul>
 *   <li>POST /executor/beat      — 执行器心跳, 维持在线状态</li>
 *   <li>POST /executor/run       — 记录一行"已下发"的调度日志, content 回带 logId(不在本机执行)</li>
 *   <li>POST /executor/callback  — 用 handleCode/handleMsg/handleTime 回写那一行日志</li>
 *   <li>POST /executor/kill      — 终止任务执行</li>
 *   <li>GET  /executor/log       — 拉取指定日志的执行结果</li>
 *   <li>GET  /executor/activeCount — 在线执行器分组数</li>
 * </ul>
 */
@RestController
@RequestMapping("/executor")
public class ExecutorCallbackController {

    private static final Logger logger = LogManager.getLogger(ExecutorCallbackController.class);

    @Autowired
    private ExecutorRegistryService registryService;

    @Autowired
    private JobLogService jobLogService;

    @Autowired
    private JobInfoService jobInfoService;

    @Autowired
    private JobTriggerService jobTriggerService;

    /**
     * 执行器心跳(同步 path 参数 + body 两种传参方式).
     */
    @PostMapping("/beat")
    public ReturnT<String> beat(@RequestParam(required = false) String appName,
                                @RequestParam(required = false) String address,
                                @RequestBody(required = false) Map<String, Object> body) {
        // 只对"真正取来用"的那个字段判类型：直接强转会把调用方写错的字段类型变成 HTTP 500
        if (appName == null && body != null) {
            Object appField = body.get("appName");
            if (appField != null && !(appField instanceof String)) {
                return ReturnT.fail(400, "appName 必须是字符串");
            }
            appName = (String) appField;
        }
        if (address == null && body != null) {
            Object addressField = body.get("address");
            if (addressField != null && !(addressField instanceof String)) {
                return ReturnT.fail(400, "address 必须是字符串");
            }
            address = (String) addressField;
        }
        if (appName == null || address == null) {
            return ReturnT.fail("appName/address 不能为空");
        }
        return registryService.beat(appName, address);
    }

    /**
     * 触发任务执行(由执行器侧 RPC 拉取).
     *
     * @param triggerParam 触发参数,执行器根据 jobId/executorHandler/executorParams 执行任务
     * @return content 携带本次派发的 {@code logId}; 执行器必须以它为键回调 {@code /executor/callback}
     */
    @PostMapping("/run")
    public ReturnT<Map<String, Object>> run(@RequestBody TriggerParam triggerParam) {
        logger.info("Executor run, param={}", triggerParam);
        if (triggerParam == null || triggerParam.getJobId() <= 0) {
            return ReturnT.fail(400, "jobId 不能为空");
        }
        // 此处不直接执行,而是记录一行"已下发"的调度日志(实际执行由执行器侧完成),
        // 真正的执行结果通过 /executor/callback 异步回写到这一行上.
        //
        // jobGroup 必须从库里那一行取，不能写死 0：日志上的组是 /joblog/* 按组收口唯一的依据，
        // 写成 0 会让这一行在所有组的过滤里同时隐形（含统计窗口），而界面上看不出任何异常。
        // 顺带把"任务不存在也照样落一行"这条路堵掉——孤儿行正是 join 式清理删不掉的那种形状（见 e2e README 坑 4）。
        JobInfo job = jobInfoService.getById(triggerParam.getJobId());
        if (job == null) {
            return ReturnT.fail("jobId=" + triggerParam.getJobId() + " 不存在，未记录派发日志");
        }
        JobLog log = new JobLog();
        log.setJobId(triggerParam.getJobId());
        log.setJobGroup(job.getJobGroup());
        log.setExecutorHandler(triggerParam.getExecutorHandler());
        log.setExecutorParam(triggerParam.getExecutorParams());
        // 调度时间必须由下发这一刻落库: statsBetween/dailyStats 都以 trigger_time 为窗口列,
        // 留空的日志在统计里永久隐形
        log.setTriggerTime(new Date());
        log.setTriggerCode(ReturnT.SUCCESS_CODE);
        log.setTriggerMsg("已下发到执行器");
        log.setExecutorFailRetryCount(triggerParam.getExecutorFailRetryCount());
        long logId = jobLogService.save(log);
        Map<String, Object> content = new HashMap<>();
        content.put("logId", logId);
        return ReturnT.success("已派发", content);
    }

    /**
     * 终止任务（由执行器调用上报）。
     * <p>
     * {@code jobId} 与 {@code logId} 必须指向同一次执行：只认 logId 会让任何一个持 token 的执行器
     * 终止别的任务的日志；而 logId 定位不到日志时返回成功，等于告诉执行器"已经停了"。
     */
    @PostMapping("/kill")
    public ReturnT<String> kill(@RequestBody KillParam killParam) {
        logger.info("Executor kill, param={}", killParam);
        if (killParam == null || killParam.getJobId() <= 0) {
            return ReturnT.fail(400, "jobId 不能为空");
        }
        if (killParam.getLogId() <= 0) {
            return ReturnT.fail(400, "logId 不能为空");
        }
        JobLog log = jobLogService.getById(killParam.getLogId());
        if (log == null || log.getId() <= 0) {
            return ReturnT.fail("日志不存在");
        }
        if (log.getJobId() != killParam.getJobId()) {
            return ReturnT.fail("logId " + log.getId() + " 属于 jobId=" + log.getJobId() + ", 与请求的 jobId 不符");
        }
        jobTriggerService.killJob(log.getId());
        return ReturnT.success("已终止任务", null);
    }

    /**
     * 拉取指定日志的执行结果(由执行器回调).
     */
    @GetMapping("/log")
    public ReturnT<JobLog> log(@RequestParam long logId,
                               @RequestParam(required = false) Long logDateTim,
                               @RequestParam(required = false, defaultValue = "0") int fromLineNum) {
        JobLog log = jobLogService.getById(logId);
        if (log == null) {
            return ReturnT.fail("日志不存在");
        }
        return ReturnT.success(log);
    }

    /**
     * 简化版:接收执行器回调的执行结果(覆盖 handleCode/handleMsg).
     * <p>
     * 只认执行侧的三个字段。执行器回传的是一整个 {@link JobLog}，而它的 int 字段
     * (jobId/jobGroup/triggerCode/alarmStatus) 在没赋值时是 0 而不是 null——
     * MyBatis-Plus 的 {@code updateById} 按 null 决定是否进 SET 子句，
     * 直接拿回调体去更新会把这条日志的归属和调度结果一并写成 0。
     * 因此这里以库里的行为底稿，只覆盖 handle_* 三列。
     * <p>
     * 已知边界（有意保持现状）：回写是 last-write-wins 的，只挡"时间戳更早的乱序回调"；
     * 一条比库里更新的回调即便与终止记录冲突仍会生效。执行失败的告警目前也只覆盖
     * 进程内执行链路，回调链路不触发 {@code AlarmService}。
     */
    @PostMapping("/callback")
    public ReturnT<String> callback(@RequestBody JobLog log) {
        if (log == null || log.getId() <= 0) {
            return ReturnT.fail("logId 不能为空");
        }
        JobLog stored = jobLogService.getById(log.getId());
        if (stored == null || stored.getId() <= 0) {
            return ReturnT.fail("日志不存在");
        }
        // 缺省按"此刻"计：回调若不落时间，列表页的执行时间列会永久空白
        Date handleTime = log.getHandleTime() == null ? new Date() : log.getHandleTime();
        if (stored.getHandleTime() != null && handleTime.before(stored.getHandleTime())) {
            // 重发/乱序的旧回调：结果已经更新，不能用旧结论盖掉它。
            // 仍报成功，否则执行器会对一条已完成的回调无限重试
            logger.info("[z-schedule] 忽略早于已记录结果的回调, logId={}, 回调时间={}, 已记录时间={}",
                    log.getId(), handleTime, stored.getHandleTime());
            return ReturnT.success("回调早于已记录的结果，已忽略", null);
        }
        stored.setHandleCode(log.getHandleCode());
        stored.setHandleMsg(log.getHandleMsg());
        stored.setHandleTime(handleTime);
        jobLogService.update(stored);
        return ReturnT.success();
    }

    /**
     * 简化版:在线执行器数量(轻量 health 探针).
     */
    @GetMapping("/activeCount")
    public ReturnT<Integer> activeCount() {
        return ReturnT.success(registryService.onlineGroupCount());
    }
}