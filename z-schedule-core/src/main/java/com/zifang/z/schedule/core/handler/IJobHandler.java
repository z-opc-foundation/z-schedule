package com.zifang.z.schedule.core.handler;

import com.zifang.z.schedule.core.model.ReturnT;
import com.zifang.z.schedule.core.param.TriggerParam;

/**
 * 任务处理器接口
 * 业务方实现此接口来定义具体的任务逻辑
 */
public interface IJobHandler {

    /**
     * 执行任务
     *
     * @param triggerParam 触发参数
     * @return 执行结果
     * @throws Exception 执行异常
     */
    ReturnT<String> execute(TriggerParam triggerParam) throws Exception;

    /**
     * 初始化回调（可选）
     * 在任务首次执行前调用
     *
     * @throws Exception 初始化异常
     */
    default void init() throws Exception {
    }

    /**
     * 销毁回调（可选）
     * 在任务执行器停止时调用
     *
     * @throws Exception 销毁异常
     */
    default void destroy() throws Exception {
    }
}
