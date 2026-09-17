package io.github.yuku123.z.schedule.core.route.impl;

import io.github.yuku123.z.schedule.core.route.ExecutorRouter;

import java.util.List;

/**
 * 固定最后一个路由策略
 * <p>
 * 始终选择地址列表中的最后一个执行器。
 */
public class LastRouter implements ExecutorRouter {

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }
        return addressList.get(addressList.size() - 1);
    }
}
