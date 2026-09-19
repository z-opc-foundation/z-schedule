package com.zifang.z.schedule.core.enums;

/**
 * 任务状态枚举
 */
public enum JobStatusEnum {

    /**
     * 停止
     */
    STOPPED(0, "停止"),

    /**
     * 运行中
     */
    RUNNING(1, "运行中");

    private final int code;
    private final String desc;

    JobStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static JobStatusEnum match(int code) {
        for (JobStatusEnum item : JobStatusEnum.values()) {
            if (item.getCode() == code) {
                return item;
            }
        }
        return null;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
