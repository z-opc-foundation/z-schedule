package com.zifang.z.schedule.web.service;

import com.zifang.z.schedule.core.model.ReturnT;

import java.util.List;

/**
 * GLUE 源码管理服务接口
 */
public interface GlueService {

    /**
     * 保存 GLUE 源码
     *
     * @param jobId      任务ID
     * @param glueSource GLUE 源码
     * @param glueType   GLUE 类型
     * @return 操作结果
     */
    ReturnT<String> save(int jobId, String glueSource, String glueType);

    /**
     * 获取最新 GLUE 源码
     *
     * @param jobId 任务ID
     * @return GLUE 源码
     */
    ReturnT<String> get(int jobId);

    /**
     * 获取 GLUE 源码历史版本列表
     *
     * @param jobId 任务ID
     * @return 版本列表（从新到旧）
     */
    ReturnT<List<String>> getVersions(int jobId);
}
