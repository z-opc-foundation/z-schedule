package io.github.yuku123.z.schedule.web.service;

import io.github.yuku123.z.schedule.core.model.JobInfo;
import io.github.yuku123.z.schedule.core.model.ReturnT;

import java.util.List;

/**
 * 任务信息服务接口
 */
public interface JobInfoService {

    /**
     * 根据ID查询任务
     *
     * @param id 任务ID
     * @return 任务信息
     */
    JobInfo getById(int id);

    /**
     * 查询所有任务
     *
     * @return 任务列表
     */
    List<JobInfo> getAll();

    /**
     * 根据执行器分组查询任务
     *
     * @param jobGroup 执行器分组ID
     * @return 任务列表
     */
    List<JobInfo> getByJobGroup(int jobGroup);

    /**
     * 新增任务
     *
     * @param jobInfo 任务信息
     * @return 操作结果
     */
    ReturnT<String> add(JobInfo jobInfo);

    /**
     * 更新任务
     *
     * @param jobInfo 任务信息
     * @return 操作结果
     */
    ReturnT<String> update(JobInfo jobInfo);

    /**
     * 删除任务
     *
     * @param id 任务ID
     * @return 操作结果
     */
    ReturnT<String> delete(int id);

    /**
     * 暂停任务
     *
     * @param id 任务ID
     * @return 操作结果
     */
    ReturnT<String> stop(int id);

    /**
     * 恢复任务
     *
     * @param id 任务ID
     * @return 操作结果
     */
    ReturnT<String> start(int id);

    /**
     * 触发任务执行
     *
     * @param id 任务ID
     * @return 操作结果
     */
    ReturnT<String> trigger(int id);

    /**
     * 获取下一次执行时间
     *
     * @param cron Cron表达式
     * @return 下次执行时间列表
     */
    ReturnT<List<String>> nextTriggerTime(String cron);
}
