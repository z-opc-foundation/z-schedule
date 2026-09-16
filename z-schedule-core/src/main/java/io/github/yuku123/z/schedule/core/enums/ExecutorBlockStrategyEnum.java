package io.github.yuku123.z.schedule.core.enums;

/**
 * 阻塞处理策略枚举
 * 当任务到达触发时间，但上一次执行尚未完成时的处理策略
 */
public enum ExecutorBlockStrategyEnum {

    /**
     * 串行执行（默认）
     * 新任务排队等待，直到上次执行完成
     */
    SERIAL_EXECUTION("SERIAL_EXECUTION", "串行执行"),

    /**
     * 丢弃后续调度
     * 直接丢弃新任务，记录日志
     */
    DISCARD_LATER("DISCARD_LATER", "丢弃后续调度"),

    /**
     * 覆盖之前调度
     * 尝试中断上次执行，开始新任务
     */
    COVER_EARLY("COVER_EARLY", "覆盖之前调度");

    private final String code;
    private final String desc;

    ExecutorBlockStrategyEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ExecutorBlockStrategyEnum match(String code) {
        for (ExecutorBlockStrategyEnum item : ExecutorBlockStrategyEnum.values()) {
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
