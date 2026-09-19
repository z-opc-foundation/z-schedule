package com.zifang.z.schedule.core.enums;

/**
 * 触发结果代码枚举
 */
public enum TriggerCodeEnum {

    /**
     * 成功
     */
    SUCCESS(200, "成功"),

    /**
     * 失败
     */
    FAIL(500, "失败"),

    /**
     * 超时
     */
    TIMEOUT(502, "超时"),

    /**
     * 执行器不存在
     */
    EXECUTOR_NOT_FOUND(404, "执行器不存在"),

    /**
     * 执行器阻塞
     */
    EXECUTOR_BLOCKED(503, "执行器阻塞"),

    /**
     * 参数非法
     */
    INVALID_PARAM(400, "参数非法");

    private final int code;
    private final String desc;

    TriggerCodeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
