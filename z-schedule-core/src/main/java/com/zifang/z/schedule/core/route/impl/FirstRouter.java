package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;

/**
 * 固定第一个路由策略
 * <p>
 * 始终选择地址列表中的第一个执行器。
 */
public class FirstRouter implements ExecutorRouter {

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }
        return addressList.get(0);
    }
}
