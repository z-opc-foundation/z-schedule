package com.zifang.z.schedule.core.enums;

/**
 * 调度过期策略枚举（Misfire Strategy）
 * <p>
 * 当调度中心因故障或重启导致任务错过调度时间时的补偿策略。
 */
public enum MisfireStrategyEnum {

    /**
     * 不做处理（默认）
     * <p>
     * 过期的调度直接跳过，仅计算下次触发时间并重新挂入时间轮。
     */
    DO_NOTHING("DO_NOTHING", "不做处理"),

    /**
     * 立即补偿触发一次
     * <p>
     * 过期的调度立即补偿执行一次，然后重新计算下次触发时间。
     */
    FIRE_ONCE_NOW("FIRE_ONCE_NOW", "立即补偿触发一次");

    private final String code;
    private final String desc;

    MisfireStrategyEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static MisfireStrategyEnum match(String code) {
        for (MisfireStrategyEnum item : MisfireStrategyEnum.values()) {
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
