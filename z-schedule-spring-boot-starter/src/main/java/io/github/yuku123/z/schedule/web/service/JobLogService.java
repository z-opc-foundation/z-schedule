package io.github.yuku123.z.schedule.web.service;

import io.github.yuku123.z.schedule.core.model.JobLog;

import java.util.List;

/**
 * 任务调度日志服务接口
 */
public interface JobLogService {

    /**
     * 写入一条调度日志.
     *
     * @param jobLog 日志实体
     * @return 新日志主键 ID
     */
    long save(JobLog jobLog);

    /**
     * 更新调度/执行结果(由调度完成回调).
     *
     * @param jobLog 日志实体(必须包含 id)
     */
    void update(JobLog jobLog);

    /**
     * 根据主键 ID 查询日志.
     *
     * @param id 主键 ID
     * @return 日志实体
     */
    JobLog getById(long id);

    /**
     * 多条件分页查询日志.
     *
     * @param jobGroup   任务分组,0 表示不过滤
     * @param jobId      任务 ID,0 表示不过滤
     * @param handleCode 执行结果码,负数表示不过滤
     * @param limit      返回上限
     * @return 倒序(最新在前)的日志列表
     */
    List<JobLog> query(int jobGroup, int jobId, int handleCode, int limit);

    /**
     * 按任务 ID 清理日志.
     *
     * @param jobId 任务 ID
     * @return 删除数量
     */
    int clearByJobId(int jobId);

    /**
     * 清空全部日志.
     *
     * @return 删除数量
     */
    int clearAll();

    /**
     * 清理超过指定天数的日志.
     *
     * @param days 保留天数（清理 days 天之前的日志）
     * @return 删除数量
     */
    int clearLogByDays(int days);
}