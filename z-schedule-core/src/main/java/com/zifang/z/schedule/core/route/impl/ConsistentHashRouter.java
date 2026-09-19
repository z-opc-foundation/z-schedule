package com.zifang.z.schedule.core.route.impl;

import com.zifang.z.schedule.core.route.ExecutorRouter;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 一致性哈希路由策略
 */
public class ConsistentHashRouter implements ExecutorRouter {

    /**
     * 虚拟节点数
     */
    private static final int VIRTUAL_NODES = 150;

    @Override
    public String route(List<String> addressList, int jobId) {
        if (addressList == null || addressList.isEmpty()) {
            return null;
        }

        // 构建哈希环
        TreeMap<Long, String> hashRing = new TreeMap<>();
        for (String address : addressList) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                long hash = hash(address + "#" + i);
                hashRing.put(hash, address);
            }
        }

        // 计算jobId的哈希值
        long jobHash = hash(String.valueOf(jobId));

        // 找到顺时针方向的第一个节点
        SortedMap<Long, String> tailMap = hashRing.tailMap(jobHash);
        Long targetKey = tailMap.isEmpty() ? hashRing.firstKey() : tailMap.firstKey();

        return hashRing.get(targetKey);
    }

    /**
     * FNV1_32_HASH算法
     */
    private long hash(String key) {
        final int p = 16777619;
        int hash = (int) 2166136261L;
        for (int i = 0; i < key.length(); i++) {
            hash = (hash ^ key.charAt(i)) * p;
        }
        hash += hash << 13;
        hash ^= hash >> 7;
        hash += hash << 3;
        hash ^= hash >> 17;
        hash += hash << 5;
        return hash & 0x7FFFFFFF;
    }
}
