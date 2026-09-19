package com.zifang.z.schedule.core.route;

import java.util.List;

/**
 * 执行器路由策略接口
 */
public interface ExecutorRouter {

    /**
     * 路由选择执行器地址
     *
     * @param addressList 可用执行器地址列表
     * @param jobId       任务ID（用于一致性哈希等策略）
     * @return 选中的执行器地址
     */
    String route(List<String> addressList, int jobId);
}
