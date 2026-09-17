package io.github.yuku123.z.schedule.core.enums;

/**
 * GLUE 类型枚举
 */
public enum GlueTypeEnum {

    BEAN("BEAN", "Bean模式"),
    GLUE_JAVA("GLUE(Java)", "Java(Groovy)"),
    GLUE_SHELL("GLUE(Shell)", "Shell脚本"),
    GLUE_PYTHON("GLUE(Python)", "Python脚本");

    private final String code;
    private final String desc;

    GlueTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据 code 匹配枚举值
     *
     * @param code GLUE 类型编码
     * @return 匹配的枚举值，不匹配返回 null
     */
    public static GlueTypeEnum match(String code) {
        if (code != null) {
            for (GlueTypeEnum item : GlueTypeEnum.values()) {
                if (item.getCode().equals(code)) {
                    return item;
                }
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
