/**
 * z-schedule 前端类型 —— 与后端 controller 的响应结构一一对应。
 *
 * 键名口径：/api/schedule/jobgroup/list 是 Map 直出（JobGroupServiceImpl.toFrontendList），
 * 所以列表项本来就是驼峰且带 registryList，不走蛇形转换；这里照抄那份 map 的键，不要按 DO 的
 * orderNum 想当然 —— DO 叫 orderNum，map 里叫 order。
 */

/** 执行器注册方式。0=自动注册（执行器 /executor/beat 上报），1=手动录入机器地址。 */
export enum AddressType {
    AUTO = 0,
    MANUAL = 1,
}

/** /api/schedule/jobgroup/list 的单项（JobGroupServiceImpl.toFrontendList 的产出）。 */
export interface JobGroup {
    id: number;
    appName: string;
    title: string;
    /** 注意是 order 不是 orderNum —— DO 字段名与 map 键名在这一个点上不一致。 */
    order?: number;
    addressType: AddressType;
    /** 手动录入的机器地址，多地址逗号分隔；自动注册时为 null。 */
    addressList?: string;
    /** 由 addressList 逗号切出来的在线节点；空列表表示未注册。 */
    registryList?: string[];
    updateTime?: string | number;
}

/** /api/schedule/joblog/list 的单项。 */
export interface JobLog {
    id: number;
    jobGroup?: number;
    jobId?: number;
    executorAddress?: string;
    executorHandler?: string;
    executorParam?: string;
    executorShardingParam?: string;
    executorFailRetryCount?: number;
    triggerTime?: string | number;
    /** 200=调度成功 500=调度失败 */
    triggerCode?: number;
    triggerMsg?: string;
    handleTime?: string | number;
    /** 200=执行成功 500=执行失败 */
    handleCode?: number;
    handleMsg?: string;
    /** 0=默认 1=无需告警 2=告警成功 3=告警失败 */
    alarmStatus?: number;
}

/** /api/schedule/joblog/list 的查询条件。 */
export interface JobLogQuery {
    jobId?: number;
    jobGroup?: number;
    /** 200 成功 / 500 失败；不传表示不过滤 */
    status?: number;
    fromTime?: string;
    toTime?: string;
}
