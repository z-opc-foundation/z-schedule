package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机路由策略
 */
public class RandomRouter implements ExecutorRouter {

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }

        int index = ThreadLocalRandom.current().nextInt(addressList.size());
        return addressList.get(index);
    }
}
