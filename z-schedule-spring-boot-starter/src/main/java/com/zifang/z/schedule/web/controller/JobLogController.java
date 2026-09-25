package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.JobLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务日志管理 Controller,提供调度日志的查询、清理、执行日志查看等接口.
 * <p>
 * API 基础路径: /joblog
 * 所属模块: z-schedule-admin
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET  /joblog/list                      — 日志分页查询(支持 jobId/jobGroup/status 过滤)</li>
 *   <li>GET  /joblog/{id}                      — 查询单条日志详情(与 /joblog/get?id= 等价)</li>
 *   <li>GET  /joblog/executionLog?logId=        — 获取执行日志(handleMsg 内容)</li>
 *   <li>POST /joblog/clear                      — 清理日志(支持 byJobId / all)</li>
 * </ul>
 */
@RestController
@RequestMapping("/joblog")
public class JobLogController {

    @Autowired
    private JobLogService jobLogService;

    /**
     * 日志分页查询.
     *
     * @param jobId    任务 ID,默认 0 不过滤
     * @param jobGroup 任务组,默认 0 不过滤
     * @param status   状态过滤,0=全部 / 1=成功 / 2=失败(任何非成功码:500/502/404/503/400)
     * @param limit    返回上限
     * @return 日志列表(按 triggerTime 倒序)
     */
    @GetMapping("/list")
    public ReturnT<List<JobLog>> list(@RequestParam(required = false, defaultValue = "0") int jobId,
                                      @RequestParam(required = false, defaultValue = "0") int jobGroup,
                                      @RequestParam(required = false, defaultValue = "0") int status,
                                      @RequestParam(required = false, defaultValue = "100") int limit) {
        int handleCode = -1;
        if (status == 1) {
            handleCode = ReturnT.SUCCESS_CODE;
        } else if (status == 2) {
            handleCode = JobLogService.ANY_FAILURE;
        }
        return ReturnT.success(jobLogService.query(jobGroup, jobId, handleCode, limit));
    }

    /**
     * 根据主键 ID 查询日志详情.
     * <p>
     * RESTful 别名: GET /joblog/{id}.
     */
    @GetMapping({"/get", "/{id}"})
    public ReturnT<JobLog> getById(@RequestParam(required = false) Long id,
                                   @PathVariable(value = "id", required = false) Long id2) {
        long realId = id != null ? id : (id2 != null ? id2 : 0L);
        JobLog log = jobLogService.getById(realId);
        if (log == null) {
            return ReturnT.fail("日志不存在");
        }
        return ReturnT.success(log);
    }

    /**
     * 获取执行日志(返回 handleMsg 内容及行号,模拟 xxl-job 的 executionLog).
     */
    @GetMapping("/executionLog")
    public ReturnT<Map<String, Object>> executionLog(@RequestParam Long logId,
                                                     @RequestParam(required = false, defaultValue = "0") int fromLineNum) {
        JobLog log = jobLogService.getById(logId);
        if (log == null) {
            return ReturnT.fail("日志不存在");
        }
        Map<String, Object> result = new HashMap<>();
        String content = log.getHandleMsg() == null ? "" : log.getHandleMsg();
        result.put("content", content);
        result.put("fromLineNum", fromLineNum);
        result.put("toLineNum", content.split("\n").length);
        result.put("isEnd", true);
        return ReturnT.success(result);
    }

    /**
     * 清理日志.
     *
     * @param body {type: 1=按 jobId,0=全部; jobId?: number}
     */
    @PostMapping("/clear")
    public ReturnT<String> clear(@RequestBody(required = false) Map<String, Object> body) {
        int type = 0;
        int jobId = 0;
        if (body != null) {
            Object t = body.get("type");
            if (t instanceof Number) type = ((Number) t).intValue();
            Object j = body.get("jobId");
            if (j instanceof Number) jobId = ((Number) j).intValue();
        }
        int removed;
        if (type == 1) {
            if (jobId <= 0) {
                return ReturnT.fail("按 jobId 清理时 jobId 必须 > 0");
            }
            removed = jobLogService.clearByJobId(jobId);
        } else {
            removed = jobLogService.clearAll();
        }
        return ReturnT.success("已清理 " + removed + " 条日志", null);
    }
}
