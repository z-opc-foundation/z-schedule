package com.zifang.z.schedule.core.enums;

/**
 * 执行器路由策略枚举
 */
public enum ExecutorRouteStrategyEnum {

    /**
     * 轮询
     */
    ROUND("ROUND", "轮询"),

    /**
     * 随机
     */
    RANDOM("RANDOM", "随机"),

    /**
     * 一致性哈希
     */
    CONSISTENT_HASH("CONSISTENT_HASH", "一致性哈希"),

    /**
     * 最近最少使用
     */
    LRU("LRU", "最近最少使用"),

    /**
     * 故障转移
     */
    FAILOVER("FAILOVER", "故障转移"),

    /**
     * 分片广播
     */
    SHARDING_BROADCAST("SHARDING_BROADCAST", "分片广播"),

    /**
     * 固定第一个
     */
    FIRST("FIRST", "固定第一个"),

    /**
     * 固定最后一个
     */
    LAST("LAST", "固定最后一个"),

    /**
     * 最不经常使用
     */
    LFU("LFU", "最不经常使用"),

    /**
     * 忙碌转移
     */
    BUSYOVER("BUSYOVER", "忙碌转移");

    private final String code;
    private final String desc;

    ExecutorRouteStrategyEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ExecutorRouteStrategyEnum match(String code) {
        for (ExecutorRouteStrategyEnum item : ExecutorRouteStrategyEnum.values()) {
            if (item.getCode().equals(code)) {
                return item;
            }
        }
        return null;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
