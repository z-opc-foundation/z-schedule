package io.github.yuku123.z.schedule.web.controller;

import io.github.yuku123.z.schedule.core.model.JobLog;
import io.github.yuku123.z.schedule.core.model.ReturnT;
import io.github.yuku123.z.schedule.core.param.KillParam;
import io.github.yuku123.z.schedule.core.param.TriggerParam;
import io.github.yuku123.z.schedule.web.service.ExecutorRegistryService;
import io.github.yuku123.z.schedule.web.service.JobLogService;
import io.github.yuku123.z.schedule.web.service.JobTriggerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
 *   <li>POST /executor/beat   — 执行器心跳, 维持在线状态</li>
 *   <li>POST /executor/run    — 触发执行器运行指定任务(直接代理到本地 JobTriggerService)</li>
 *   <li>POST /executor/kill   — 终止任务执行</li>
 *   <li>GET  /executor/log    — 拉取指定日志的执行结果</li>
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
    private JobTriggerService jobTriggerService;

    /**
     * 执行器心跳(同步 path 参数 + body 两种传参方式).
     */
    @PostMapping("/beat")
    public ReturnT<String> beat(@RequestParam(required = false) String appName,
                                @RequestParam(required = false) String address,
                                @RequestBody(required = false) Map<String, Object> body) {
        if (appName == null && body != null) appName = (String) body.get("appName");
        if (address == null && body != null) address = (String) body.get("address");
        if (appName == null || address == null) {
            return ReturnT.fail("appName/address 不能为空");
        }
        return registryService.beat(appName, address);
    }

    /**
     * 触发任务执行(由执行器侧 RPC 拉取).
     *
     * @param triggerParam 触发参数,执行器根据 jobId/executorHandler/executorParams 执行任务
     */
    @PostMapping("/run")
    public ReturnT<String> run(@RequestBody TriggerParam triggerParam) {
        logger.info("Executor run, param={}", triggerParam);
        if (triggerParam == null || triggerParam.getJobId() <= 0) {
            return ReturnT.fail(400, "jobId 不能为空");
        }
        // 此处不直接执行,而是返回 OK 表示调度中心已记录分发(实际执行由执行器侧完成).
        // 真正的执行结果通过 /executor/log 异步回传.
        JobLog log = new JobLog();
        log.setJobId(triggerParam.getJobId());
        log.setJobGroup(0);
        log.setExecutorHandler(triggerParam.getExecutorHandler());
        log.setExecutorParam(triggerParam.getExecutorParams());
        log.setTriggerCode(ReturnT.SUCCESS_CODE);
        log.setTriggerMsg("已下发到执行器");
        log.setExecutorFailRetryCount(triggerParam.getExecutorFailRetryCount());
        long logId = jobLogService.save(log);
        Map<String, Object> content = new HashMap<>();
        content.put("logId", logId);
        return ReturnT.success("已派发", null);
    }

    /**
     * 终止任务（由执行器调用上报）。
     */
    @PostMapping("/kill")
    public ReturnT<String> kill(@RequestBody KillParam killParam) {
        logger.info("Executor kill, param={}", killParam);
        if (killParam == null || killParam.getJobId() <= 0) {
            return ReturnT.fail(400, "jobId 不能为空");
        }
        // 通过 jobId 查找最近的日志并终止
        JobLog log = jobLogService.getById(killParam.getLogId());
        if (log != null && log.getId() > 0) {
            jobTriggerService.killJob(log.getId());
            return ReturnT.success("已终止任务", null);
        }
        return ReturnT.success("已通知终止", null);
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
     * 简化版:直接接收执行器回调的执行结果(覆盖 handleCode/handleMsg).
     */
    @PostMapping("/callback")
    public ReturnT<String> callback(@RequestBody JobLog log) {
        if (log == null || log.getId() <= 0) {
            return ReturnT.fail("logId 不能为空");
        }
        jobLogService.update(log);
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