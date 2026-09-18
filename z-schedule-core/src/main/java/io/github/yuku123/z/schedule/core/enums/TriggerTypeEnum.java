package io.github.yuku123.z.schedule.core.enums;

/**
 * 触发类型枚举
 */
public enum TriggerTypeEnum {

    /**
     * Cron触发
     */
    CRON("CRON", "Cron触发"),

    /**
     * 手动触发
     */
    MANUAL("MANUAL", "手动触发"),

    /**
     * 父任务触发
     */
    PARENT("PARENT", "父任务触发"),

    /**
     * API触发
     */
    API("API", "API触发"),

    /**
     * 重试触发
     */
    RETRY("RETRY", "重试触发"),

    /**
     * 固定间隔触发
     */
    FIX_RATE("FIX_RATE", "固定间隔触发"),

    /**
     * 固定延迟触发（上次完成后延迟指定时间再触发）
     */
    FIX_DELAY("FIX_DELAY", "固定延迟触发");

    private final String code;
    private final String desc;

    TriggerTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据 code 查找对应的枚举。
     *
     * @param code 枚举 code
     * @return 匹配的枚举，未匹配返回 null
     */
    public static TriggerTypeEnum match(String code) {
        if (code == null) {
            return null;
        }
        for (TriggerTypeEnum item : TriggerTypeEnum.values()) {
            if (item.getCode().equals(code)) {
                return item;
            }
        }
        return null;
    }
}
