package com.zifang.z.schedule.web.controller;

import com.zifang.z.schedule.core.model.JobInfo;
import com.zifang.z.schedule.core.model.JobLog;
import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.web.auth.GroupAccess;
import com.zifang.z.schedule.web.auth.LoginSession;
import com.zifang.z.schedule.web.service.JobInfoService;
import com.zifang.z.schedule.web.service.JobLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务日志管理 Controller,提供调度日志的查询、清理、执行日志查看等接口.
 * <p>
 * API 基础路径: /joblog
 * 所属模块: z-schedule-admin
 *
 * <p>日志行上的 {@code job_group} 是这一层唯一的判据（{@code /jobinfo/*} 判的是任务那一行），
 * 所以四条口都按 {@link GroupAccess} 收口：出示了会话令牌且 {@code permission} 写明组的请求
 * 只能看见/清理自己那组的日志；匿名、共享密钥、管理员、留空的账号一律不受约束。
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

    /** 与 {@code JobLogServiceImpl.query} 的排序一致：最新在前，同刻按 id 倒序。 */
    private static final Comparator<JobLog> NEWEST_FIRST = new Comparator<JobLog>() {
        @Override
        public int compare(JobLog left, JobLog right) {
            int byTime = newestFirst(left.getTriggerTime(), right.getTriggerTime());
            return byTime != 0 ? byTime : Long.compare(right.getId(), left.getId());
        }
    };

    @Autowired
    private JobLogService jobLogService;

    /** 日志只有 {@code job_group}，按 jobId 过滤时要判组就得先问任务那一行属于哪组。 */
    @Autowired
    private JobInfoService jobInfoService;

    /**
     * 日志分页查询.
     * <p>
     * 点名了组却没权限 ⇒ 明确拒绝（给空表会让人以为那组没日志）；只给了 jobId ⇒ 组要从任务那一行
     * 解析出来才判得了，任务不存在时无从可判，也只能拒绝（"不存在"这件事本身由 {@code /jobinfo} 说，
     * 这一层不替人确认 id 的存在与否）；什么都没点名 ⇒ 见 {@link #newestAcross}。
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
                                      @RequestParam(required = false, defaultValue = "100") int limit,
                                      HttpServletRequest request) {
        int handleCode = handleCodeOf(status);
        LoginSession who = GroupAccess.restrictable(request);
        if (who == null) {
            return ReturnT.success(jobLogService.query(jobGroup, jobId, handleCode, limit));
        }
        if (jobGroup > 0) {
            if (!who.permits(jobGroup)) {
                return ReturnT.fail(GroupAccess.denialReason(jobGroup));
            }
            return ReturnT.success(jobLogService.query(jobGroup, jobId, handleCode, limit));
        }
        if (jobId > 0) {
            JobInfo job = jobInfoService.getById(jobId);
            if (job == null) {
                return ReturnT.fail("jobId=" + jobId + " 不存在，无法判定它属于哪个 jobGroup");
            }
            if (!who.permits(job.getJobGroup())) {
                return ReturnT.fail(GroupAccess.denialReason(job.getJobGroup()));
            }
            // 判的是任务现在的组，取的是这个 job 的全部历史行：任务挪过组的话，
            // 反过来按日志行上的旧组裁剪会把历史藏起来，而"这任务现在归我"是同一行说的。
            return ReturnT.success(jobLogService.query(0, jobId, handleCode, limit));
        }
        return ReturnT.success(newestAcross(who.declaredGroups(), handleCode, limit));
    }

    /**
     * 根据主键 ID 查询日志详情.
     * <p>
     * RESTful 别名: GET /joblog/{id}.
     */
    @GetMapping({"/get", "/{id}"})
    public ReturnT<JobLog> getById(@RequestParam(required = false) Long id,
                                   @PathVariable(value = "id", required = false) Long id2,
                                   HttpServletRequest request) {
        long realId = id != null ? id : (id2 != null ? id2 : 0L);
        JobLog log = jobLogService.getById(realId);
        if (log == null) {
            return ReturnT.fail("日志不存在");
        }
        String denial = denyGroupOf(request, log);
        if (denial != null) {
            return ReturnT.fail(denial);
        }
        return ReturnT.success(log);
    }

    /**
     * 获取执行日志(返回 handleMsg 内容及行号,模拟 xxl-job 的 executionLog).
     */
    @GetMapping("/executionLog")
    public ReturnT<Map<String, Object>> executionLog(@RequestParam Long logId,
                                                     @RequestParam(required = false, defaultValue = "0") int fromLineNum,
                                                     HttpServletRequest request) {
        JobLog log = jobLogService.getById(logId);
        if (log == null) {
            return ReturnT.fail("日志不存在");
        }
        String denial = denyGroupOf(request, log);
        if (denial != null) {
            return ReturnT.fail(denial);
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
     * <p>
     * 分权会话只能清自己那组的任务（{@code type=1} 的 jobId 要解析出库里那一行的组来判），
     * <b>清空全部</b>（{@code type=0}）对分权会话直接拒绝：它删的是所有组的行，而这一层的判据
     * 恰好只有行上的 {@code job_group}——任务已被删掉的孤儿行连组都解析不出来，放进"按任务清理"
     * 里就是一条越组通道。
     *
     * @param body {type: 1=按 jobId,0=全部; jobId?: number}
     */
    @PostMapping("/clear")
    public ReturnT<String> clear(@RequestBody(required = false) Map<String, Object> body,
                                 HttpServletRequest request) {
        int type = 0;
        int jobId = 0;
        if (body != null) {
            Object t = body.get("type");
            if (t instanceof Number) type = ((Number) t).intValue();
            Object j = body.get("jobId");
            if (j instanceof Number) jobId = ((Number) j).intValue();
        }
        if (type == 1 && jobId <= 0) {
            return ReturnT.fail("按 jobId 清理时 jobId 必须 > 0");
        }
        LoginSession who = GroupAccess.restrictable(request);
        if (who != null) {
            if (type != 1) {
                return ReturnT.fail("当前会话只按 z_schedule_user.permission 授权到具体 jobGroup，"
                        + "清空全部日志需要管理员会话或共享密钥");
            }
            JobInfo job = jobInfoService.getById(jobId);
            if (job == null) {
                return ReturnT.fail("jobId=" + jobId + " 不存在，无法判定它属于哪个 jobGroup，未清理");
            }
            if (!who.permits(job.getJobGroup())) {
                return ReturnT.fail(GroupAccess.denialReason(job.getJobGroup()));
            }
        }
        int removed = type == 1 ? jobLogService.clearByJobId(jobId) : jobLogService.clearAll();
        return ReturnT.success("已清理 " + removed + " 条日志", null);
    }

    /** {@code status} 参数到 handleCode 的翻译：0=不过滤 / 1=成功码 / 2=所有失败码。 */
    private static int handleCodeOf(int status) {
        if (status == 1) {
            return ReturnT.SUCCESS_CODE;
        }
        return status == 2 ? JobLogService.ANY_FAILURE : -1;
    }

    /**
     * 这一行日志所属的组，分权会话碰不碰得到。
     * <p>
     * 只在"出示了分权会话"时才判组：日志行本来就带着 {@code job_group}，所以不受约束的身份
     * （{@link GroupAccess#restrictable} 回 {@code null}）不为这一眼多打一次库。
     *
     * @return {@code null} 表示放行，否则是拒绝理由
     */
    private static String denyGroupOf(HttpServletRequest request, JobLog row) {
        LoginSession who = GroupAccess.restrictable(request);
        if (who == null || who.permits(row.getJobGroup())) {
            return null;
        }
        return GroupAccess.denialReason(row.getJobGroup());
    }

    /**
     * 分权会话的"不限组"查询：每个允许的组各查一页，再按调度时间倒序合并、截到同一个上限。
     * <p>
     * 不能"先全局查 limit 条再裁剪"——别的组的日志更密时，全局那一页里可能一条自己那组的都没有，
     * 于是明明库里还有碰得到的行，界面却给了空表或不足 limit 的结果。逐组查再合并等价于
     * {@code WHERE job_group IN (...) ORDER BY trigger_time DESC LIMIT n}，代价是组数次查询，
     * 而这一列是人手填的、组数是个位数。
     * <p>
     * 截断必须用 {@link JobLogService#effectiveLimit(int)}：{@code query} 内部会把 limit 收窄到
     * {@code MAX_PAGE_SIZE}，调用方按原始 limit 截会在 {@code limit>1000} 时超发（N 组 × 1000 行）。
     */
    private List<JobLog> newestAcross(List<Integer> groups, int handleCode, int limit) {
        int cap = JobLogService.effectiveLimit(limit);
        List<JobLog> merged = new ArrayList<JobLog>();
        for (Integer group : groups) {
            merged.addAll(jobLogService.query(group, 0, handleCode, cap));
        }
        Collections.sort(merged, NEWEST_FIRST);
        return merged.size() <= cap ? merged : new ArrayList<JobLog>(merged.subList(0, cap));
    }

    /** 时间倒序；{@code trigger_time} 为空排最后——这种行落在任何时间窗口之外，不该占掉一页的名额。 */
    private static int newestFirst(Date left, Date right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return right.compareTo(left);
    }
}
