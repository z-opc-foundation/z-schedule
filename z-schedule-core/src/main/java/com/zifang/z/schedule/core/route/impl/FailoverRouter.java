package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;

/**
 * 故障转移路由策略
 * 依次尝试每个地址，返回第一个可用的
 */
public class FailoverRouter implements ExecutorRouter {

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }

        // 这里只是返回第一个地址
        // 实际的故障转移逻辑应该在调用方实现
        // 当调用失败时，尝试下一个地址
        return addressList.get(0);
    }
}
