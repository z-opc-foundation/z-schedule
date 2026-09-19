package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.service.JobInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 调度任务管理 Controller,提供定时任务的 CRUD、启停、触发以及下次执行时间查询等接口.
 * <p>
 * API 基础路径: /jobinfo
 * 所属模块: z-schedule-admin
 * 鉴权: 由调度管理端统一拦截,需登录态校验
 *
 * <p>主要端点:
 * <ul>
 *   <li>GET /jobinfo/list — 获取任务列表(可按 jobGroup 过滤)</li>
 *   <li>GET /jobinfo/get — 获取单个任务</li>
 *   <li>POST /jobinfo/add — 新增任务</li>
 *   <li>POST /jobinfo/update — 更新任务</li>
 *   <li>POST /jobinfo/remove — 删除任务</li>
 *   <li>POST /jobinfo/stop — 停止任务</li>
 *   <li>POST /jobinfo/start — 启动任务</li>
 *   <li>POST /jobinfo/trigger — 手动触发任务执行</li>
 *   <li>GET /jobinfo/nextTriggerTime — 计算 Cron 表达式的下次触发时间</li>
 * </ul>
 */
@RestController
@RequestMapping("/jobinfo")
public class JobInfoController {

    @Autowired
    private JobInfoService jobInfoService;

    /**
     * 获取任务列表,当 jobGroup 大于 0 时按任务组过滤,否则返回全部.
     *
     * @param jobGroup 任务组 ID,默认 0 表示不过滤
     * @return 任务列表的封装结果
     */
    @GetMapping("/list")
    public ReturnT<List<JobInfo>> list(@RequestParam(required = false, defaultValue = "0") int jobGroup) {
        List<JobInfo> list;
        if (jobGroup > 0) {
            list = jobInfoService.getByJobGroup(jobGroup);
        } else {
            list = jobInfoService.getAll();
        }
        return ReturnT.success(list);
    }

    /**
     * 根据主键 ID 获取单个任务详情,不存在时返回失败.
     * <p>
     * 同时作为 /jobinfo/get?id={id} 的 RESTful 别名(/jobinfo/{id}).
     *
     * @param id 任务主键 ID
     * @return 任务详情的封装结果
     */
    @GetMapping({"/get", "/{id}"})
    public ReturnT<JobInfo> getById(@RequestParam(required = false) Integer id,
                                    @PathVariable(value = "id", required = false) Integer id2) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        JobInfo jobInfo = jobInfoService.getById(realId);
        if (jobInfo == null) {
            return ReturnT.fail("任务不存在");
        }
        return ReturnT.success(jobInfo);
    }

    /**
     * 新增任务.
     *
     * @param jobInfo 待新增的任务实体
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping("/add")
    public ReturnT<String> add(@RequestBody JobInfo jobInfo) {
        return jobInfoService.add(jobInfo);
    }

    /**
     * 更新任务.
     *
     * @param jobInfo 待更新的任务实体
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping("/update")
    public ReturnT<String> update(@RequestBody JobInfo jobInfo) {
        return jobInfoService.update(jobInfo);
    }

    /**
     * 根据主键 ID 删除任务.
     * <p>
     * RESTful 别名: POST /jobinfo/remove/{id}
     *
     * @param id 任务主键 ID
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping({"/remove", "/remove/{id}"})
    public ReturnT<String> remove(@RequestParam(required = false) Integer id,
                                  @PathVariable(value = "id", required = false) Integer id2) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        return jobInfoService.delete(realId);
    }

    /**
     * 停止指定任务.
     * <p>
     * RESTful 别名: POST /jobinfo/stop/{id}
     *
     * @param id 任务主键 ID
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping({"/stop", "/stop/{id}"})
    public ReturnT<String> stop(@RequestParam(required = false) Integer id,
                                @PathVariable(value = "id", required = false) Integer id2) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        return jobInfoService.stop(realId);
    }

    /**
     * 启动指定任务.
     * <p>
     * RESTful 别名: POST /jobinfo/start/{id}
     *
     * @param id 任务主键 ID
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping({"/start", "/start/{id}"})
    public ReturnT<String> start(@RequestParam(required = false) Integer id,
                                 @PathVariable(value = "id", required = false) Integer id2) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        return jobInfoService.start(realId);
    }

    /**
     * 手动触发任务执行一次.
     * <p>
     * RESTful 别名: POST /jobinfo/trigger/{id}
     *
     * @param id 任务主键 ID
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping({"/trigger", "/trigger/{id}"})
    public ReturnT<String> trigger(@RequestParam(required = false) Integer id,
                                   @PathVariable(value = "id", required = false) Integer id2) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        return jobInfoService.trigger(realId);
    }

    /**
     * 根据 Cron 表达式计算未来的若干次触发时间.
     *
     * @param cron Cron 表达式
     * @return 后续触发时间字符串列表的封装结果
     */
    @GetMapping("/nextTriggerTime")
    public ReturnT<List<String>> nextTriggerTime(@RequestParam String cron) {
        return jobInfoService.nextTriggerTime(cron);
    }
}
