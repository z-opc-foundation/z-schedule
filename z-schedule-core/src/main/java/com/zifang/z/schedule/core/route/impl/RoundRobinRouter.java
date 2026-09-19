package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询路由策略
 */
public class RoundRobinRouter implements ExecutorRouter {

    /**
     * 每个任务ID对应的计数器
     */
    private static final ConcurrentMap<Integer, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }

        // 获取该任务的计数器
        AtomicInteger counter = counters.computeIfAbsent(jobId, k -> new AtomicInteger(0));

        // 获取并递增计数器
        int index = counter.getAndIncrement() % addressList.size();
        if (index < 0) {
            index = -index;
        }

        return addressList.get(index);
    }
}
