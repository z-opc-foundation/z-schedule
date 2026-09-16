package io.github.yuku123.z.schedule.web.service;

import io.github.yuku123.z.schedule.core.model.ReturnT;

/**
 * 执行器注册/心跳服务接口.
 * <p>
 * 由执行器(z-schedule-executor)周期性回调心跳,调度中心维护心跳表并清理超期节点.
 */
public interface ExecutorRegistryService {

    /**
     * 接收执行器心跳.
     *
     * @param appName 执行器 AppName
     * @param address 执行器实例地址
     * @return 操作结果(msg 字段附带当前活跃实例数)
     */
    ReturnT<String> beat(String appName, String address);

    /**
     * 移除指定节点的注册(主动下线).
     *
     * @param appName 执行器 AppName
     * @param address 执行器实例地址
     * @return 操作结果
     */
    ReturnT<String> remove(String appName, String address);

    /**
     * 当前在线的执行器分组数量(至少一个活跃节点).
     *
     * @return 分组数量
     */
    int onlineGroupCount();

    /**
     * 加载所有分组 + 实时心跳节点（供 Dashboard /executorLoad 使用）.
     */
    java.util.List<java.util.Map<String, Object>> loadAll();
}
