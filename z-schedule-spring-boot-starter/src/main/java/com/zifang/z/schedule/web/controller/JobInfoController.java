package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.auth.GroupAccess;
import com.zifang.z.schedule.web.auth.LoginSession;
import com.zifang.z.schedule.web.service.JobInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 调度任务管理 Controller,提供定时任务的 CRUD、启停、触发以及下次执行时间查询等接口.
 * <p>
 * API 基础路径: /jobinfo
 * 所属模块: z-schedule-admin
 * 鉴权: 由 {@code TokenAuthFilter} 统一校验凭证；<b>在此之上</b>，出示了普通会话的请求
 * 还要按 {@code z_schedule_user.permission} 收口到具体的 jobGroup（见 {@link GroupAccess}）。
 * 管理员会话、共享密钥、演示模式匿名都不受这一层约束——前两者本来全权，后者没有身份可问。
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
     * <p>
     * 指定了组却没权限 ⇒ 明确拒绝（不是"给你一张空表"，那会让人以为组里没任务）；
     * 没指定组 ⇒ 把结果裁剪到这个身份碰得到的组。
     *
     * @param jobGroup 任务组 ID,默认 0 表示不过滤
     * @return 任务列表的封装结果
     */
    @GetMapping("/list")
    public ReturnT<List<JobInfo>> list(@RequestParam(required = false, defaultValue = "0") int jobGroup,
                                       HttpServletRequest request) {
        LoginSession who = GroupAccess.restrictable(request);
        if (who != null && jobGroup > 0 && !who.permits(jobGroup)) {
            return ReturnT.fail(GroupAccess.denialReason(jobGroup));
        }
        List<JobInfo> list;
        if (jobGroup > 0) {
            list = jobInfoService.getByJobGroup(jobGroup);
        } else {
            list = jobInfoService.getAll();
        }
        return ReturnT.success(GroupAccess.narrow(list, who, JobInfo::getJobGroup));
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
                                    @PathVariable(value = "id", required = false) Integer id2,
                                    HttpServletRequest request) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        JobInfo jobInfo = jobInfoService.getById(realId);
        if (jobInfo == null) {
            return ReturnT.fail("任务不存在");
        }
        // 这一次读取本来就带着 jobGroup，所以按组收口对受限身份之外的人**不多打一次库**
        LoginSession who = GroupAccess.restrictable(request);
        if (who != null && !who.permits(jobInfo.getJobGroup())) {
            return ReturnT.fail(GroupAccess.denialReason(jobInfo.getJobGroup()));
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
    public ReturnT<String> add(@RequestBody JobInfo jobInfo, HttpServletRequest request) {
        LoginSession who = GroupAccess.restrictable(request);
        if (who != null && !who.permits(jobInfo.getJobGroup())) {
            return ReturnT.fail(GroupAccess.denialReason(jobInfo.getJobGroup()));
        }
        return jobInfoService.add(jobInfo);
    }

    /**
     * 更新任务.
     * <p>
     * 判**两个**组：库里现在这个任务的组、请求体里写成的组。只判后者的话，
     * "把一个看得见的任务改到看不见的组里"和"把别人的任务改组到我这边"都成了可走的路。
     *
     * @param jobInfo 待更新的任务实体
     * @return 操作结果(成功/失败以及错误信息)
     */
    @PostMapping("/update")
    public ReturnT<String> update(@RequestBody JobInfo jobInfo, HttpServletRequest request) {
        LoginSession who = GroupAccess.restrictable(request);
        if (who != null) {
            JobInfo exist = jobInfoService.getById(jobInfo.getId());
            if (exist != null) {
                if (!who.permits(exist.getJobGroup())) {
                    return ReturnT.fail(GroupAccess.denialReason(exist.getJobGroup()));
                }
                if (jobInfo.getJobGroup() != exist.getJobGroup() && !who.permits(jobInfo.getJobGroup())) {
                    return ReturnT.fail(GroupAccess.denialReason(jobInfo.getJobGroup()));
                }
            }
        }
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
                                  @PathVariable(value = "id", required = false) Integer id2,
                                  HttpServletRequest request) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        ReturnT<String> denial = denyByJobId(request, realId);
        if (denial != null) {
            return denial;
        }
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
                                @PathVariable(value = "id", required = false) Integer id2,
                                HttpServletRequest request) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        ReturnT<String> denial = denyByJobId(request, realId);
        if (denial != null) {
            return denial;
        }
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
                                 @PathVariable(value = "id", required = false) Integer id2,
                                 HttpServletRequest request) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        ReturnT<String> denial = denyByJobId(request, realId);
        if (denial != null) {
            return denial;
        }
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
                                   @PathVariable(value = "id", required = false) Integer id2,
                                   HttpServletRequest request) {
        int realId = id != null ? id : (id2 != null ? id2 : 0);
        ReturnT<String> denial = denyByJobId(request, realId);
        if (denial != null) {
            return denial;
        }
        return jobInfoService.trigger(realId);
    }

    /**
     * 只认<b>库里</b>那个任务的 jobGroup：请求参数只有一个 id，组必须解析出来才能判。
     * <p>
     * 不受约束的身份（匿名 / 共享密钥 / 管理员）在这里直接返回 {@code null}，
     * 所以现在这些调用一条额外的查询都不会多；任务不存在时也不在这里表态，
     * 让 service 去说"任务不存在"（在这里回"没有权限"等于替人确认了这个 id 存在与否的另一半）。
     *
     * @return {@code null} 表示放行，否则是拒绝理由
     */
    private ReturnT<String> denyByJobId(HttpServletRequest request, int jobId) {
        LoginSession who = GroupAccess.restrictable(request);
        if (who == null) {
            return null;
        }
        JobInfo job = jobInfoService.getById(jobId);
        if (job == null || who.permits(job.getJobGroup())) {
            return null;
        }
        return ReturnT.fail(GroupAccess.denialReason(job.getJobGroup()));
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
