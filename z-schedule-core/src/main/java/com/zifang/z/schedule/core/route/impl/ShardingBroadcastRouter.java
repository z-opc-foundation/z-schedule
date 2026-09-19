package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;

/**
 * 分片广播路由策略
 * 返回所有地址，用于广播到所有执行器
 */
public class ShardingBroadcastRouter implements ExecutorRouter {

    @Override
    public String route(List<String> addressList, int jobId) {
        // 分片广播返回特殊标记，表示需要广播到所有节点
        // 实际使用时，由调用方处理广播逻辑
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }
        return String.join(",", addressList);
    }
}
