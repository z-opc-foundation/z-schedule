package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 最近最少使用路由策略（LRU）
 * <p>
 * 按 jobId 维护每个执行器地址的最后使用时间戳，
 * 优先选择最久未使用的地址。
 */
public class LruRouter implements ExecutorRouter {

    /**
     * key: jobId + "#" + address, value: 最后使用时间戳(ms)
     */
    private static final ConcurrentMap<String, Long> lastUsedMap = new ConcurrentHashMap<>();

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }

        String minAddress = addressList.get(0);
        long minTime = lastUsedMap.getOrDefault(key(jobId, minAddress), 0L);

        for (int i = 1; i < addressList.size(); i++) {
            String address = addressList.get(i);
            long lastTime = lastUsedMap.getOrDefault(key(jobId, address), 0L);
            if (lastTime < minTime) {
                minTime = lastTime;
                minAddress = address;
            }
        }

        // 更新选中地址的使用时间
        lastUsedMap.put(key(jobId, minAddress), System.currentTimeMillis());
        return minAddress;
    }

    private static String key(int jobId, String address) {
        return jobId + "#" + address;
    }
}
