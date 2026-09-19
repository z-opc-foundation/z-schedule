package com.zifang.z.schedule.web.service;

import com.zifang.z.schedule.core.model.JobGroup;
import com.zifang.z.schedule.core.model.ReturnT;

import java.util.List;

/**
 * 执行器分组服务接口
 */
public interface JobGroupService {

    /**
     * 查询所有执行器分组
     *
     * @return 执行器分组列表
     */
    List<JobGroup> getAll();

    /**
     * 根据主键 ID 查询执行器分组
     *
     * @param id 主键 ID
     * @return 执行器分组
     */
    JobGroup getById(int id);

    /**
     * 根据 AppName 查询执行器分组
     *
     * @param appName 应用名称
     * @return 执行器分组
     */
    JobGroup getByAppName(String appName);

    /**
     * 新增执行器分组
     *
     * @param jobGroup 分组信息
     * @return 操作结果(成功时 content 为新 ID)
     */
    ReturnT<String> add(JobGroup jobGroup);

    /**
     * 更新执行器分组
     *
     * @param jobGroup 分组信息
     * @return 操作结果
     */
    ReturnT<String> update(JobGroup jobGroup);

    /**
     * 删除执行器分组
     *
     * @param id 主键 ID
     * @return 操作结果
     */
    ReturnT<String> delete(int id);

    /**
     * 注册执行器实例(供 Executor /beat 调用)
     *
     * @param appName 执行器 AppName
     * @param address 执行器地址(ip:port)
     * @return 操作结果
     */
    ReturnT<String> register(String appName, String address);

    /**
     * 获取某分组下在线的注册节点列表
     *
     * @param appName 应用名称
     * @return 注册节点地址列表
     */
    List<String> getRegistryNodes(String appName);
}