package io.github.yuku123.z.schedule.core.route.impl;

import io.github.yuku123.z.schedule.core.route.ExecutorRouter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最不经常使用路由策略（LFU）
 * <p>
 * 按 jobId 维护每个执行器地址的使用计数，
 * 优先选择使用次数最少的地址。
 */
public class LfuRouter implements ExecutorRouter {

    /**
     * key: jobId + "#" + address, value: 使用次数
     */
    private static final ConcurrentMap<String, AtomicInteger> usageCounters = new ConcurrentHashMap<>();

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }

        String minAddress = addressList.get(0);
        int minCount = getAndIncrementCount(jobId, minAddress);

        for (int i = 1; i < addressList.size(); i++) {
            String address = addressList.get(i);
            int count = getAndIncrementCount(jobId, address);
            if (count < minCount) {
                minCount = count;
                minAddress = address;
            }
        }

        return minAddress;
    }

    /**
     * 获取指定地址的当前使用次数（不递增，仅查询），用于比较。
     * 每次路由时会对最终选中的地址计数器 +1。
     */
    private int getCount(int jobId, String address) {
        String key = jobId + "#" + address;
        return usageCounters.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
    }

    /**
     * 获取当前计数，并递增。返回递增前的值。
     */
    private int getAndIncrementCount(int jobId, String address) {
        String key = jobId + "#" + address;
        return usageCounters.computeIfAbsent(key, k -> new AtomicInteger(0)).getAndIncrement();
    }
}
