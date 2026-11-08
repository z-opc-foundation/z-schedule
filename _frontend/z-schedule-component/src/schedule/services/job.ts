/**
 * z-schedule 执行器 / 日志 API 封装。
 *
 * 端点与后端一一对应（JobGroupController / JobLogController）：
 *   - GET  /api/schedule/jobgroup/list
 *   - GET  /api/schedule/jobgroup/get?id=
 *   - GET  /api/schedule/jobgroup/registryNodes?appName=
 *   - POST /api/schedule/jobgroup/add|update   body 驼峰，与 core model 的字段名一致
 *   - POST /api/schedule/jobgroup/remove?id=
 *   - GET  /api/schedule/joblog/list?jobId=&jobGroup=&status=&limit=
 *   - GET  /api/schedule/joblog/executionLog?logId=
 *   - POST /api/schedule/joblog/clear          body {type: 0=全部, 1=按 jobId}
 *
 * 为什么走 /api/schedule 而不是裸的 /jobgroup：SSO 登录拦截器只挡 /api/**，裸路径不过拦截。
 * 两个 controller 都同时挂了两条路径（见其 @RequestMapping），这里选带 /api 的那条。
 *
 * z-frontend-common 已在响应拦截器里解包 ReturnT<T>，所以业务层拿到的就是内层 data，
 * 不要再 .then(r => r.data)。
 */

import {createRequest} from '@yuku123/z-frontend-common';

import type {JobGroup, JobInfo, JobLog, JobLogQuery} from '../types';

let _baseURL = '';

export function configureScheduleBaseURL(baseURL: string) {
    _baseURL = baseURL || '';
}

function makeRequest() {
    return createRequest({baseURL: _baseURL, timeout: 15000});
}

/**
 * z-schedule 的响应是 **xxl-job 风格** `{code, msg, content[, success]}`，
 * 而 @yuku123/z-frontend-common 的 unwrapResponse 只认 `{code, data}`。
 * ⇒ 此前三个页面（任务/执行器/日志）拿到的都是**整个响应对象**，
 *    再被 `Array.isArray(...) ? ... : []` 判成空数组 ⇒ 永远显示「暂无数据」
 *    （2026-11-09 由用户「调度服务哪去了」牵出来的第二个坑：页面不是没有，是数据没解出来）。
 *
 * 修在 service 层（只影响本域三个页面），不动公共层 —— 公共层的 `content` 语义在别的域可能是正文。
 */
/**
 * 日志接口同样是 DO 直出（蛇形：job_id / executor_handler / trigger_time / handle_code …），
 * 而 types/JobLog 与 JobLogList 页面按**驼峰**读 ⇒ 此前日志表整列 "#undefined"。
 * jobgroup 例外：它走 Map 直出（本事就是驼峰），不要再转。
 */
function camelizeRow(row: any): any {
    if (!row || typeof row !== 'object') return row;
    const out: any = {};
    for (const [k, v] of Object.entries(row)) {
        out[k.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase())] = v;
    }
    return out;
}

function unwrapSchedule<T = any>(res: any): T {
    if (res && typeof res === 'object' && 'content' in res && !('data' in res)) {
        if (res.code != null && res.code !== 200) {
            throw new Error(res.msg || '请求失败');
        }
        return res.content as T;
    }
    return res as T;
}

export const jobGroupApi = {
    getList: () => makeRequest().get('/api/schedule/jobgroup/list').then(unwrapSchedule),
    get: (id: number) => makeRequest().get('/api/schedule/jobgroup/get', {params: {id}}).then(unwrapSchedule),
    registryNodes: (appName: string) =>
        makeRequest().get('/api/schedule/jobgroup/registryNodes', {params: {appName}}).then(unwrapSchedule),
    add: (data: Partial<JobGroup>) => makeRequest().post('/api/schedule/jobgroup/add', data).then(unwrapSchedule),
    update: (data: Partial<JobGroup>) => makeRequest().post('/api/schedule/jobgroup/update', data).then(unwrapSchedule),
    remove: (id: number) => makeRequest().post('/api/schedule/jobgroup/remove', null, {params: {id}}).then(unwrapSchedule),
};

/**
 * 任务台账（JobInfoController）。
 *
 * ⚠ 键名口径与 jobgroup 不同：jobgroup 走 Map 直出（驼峰），
 *   jobinfo 直接序列化 DO ⇒ **蛇形**（job_cron / job_desc / job_group / trigger_status …）。
 *   前端不要按驼峰读，否则整列都是 "-"。
 */
export const jobInfoApi = {
    getList: (params: {jobGroup?: number; triggerStatus?: number} = {}) =>
        makeRequest().get('/api/schedule/jobinfo/list', {params}).then(unwrapSchedule),
    get: (id: number) => makeRequest().get('/api/schedule/jobinfo/get', {params: {id}}).then(unwrapSchedule),
    add: (data: Partial<JobInfo>) => makeRequest().post('/api/schedule/jobinfo/add', data).then(unwrapSchedule),
    update: (data: Partial<JobInfo>) => makeRequest().post('/api/schedule/jobinfo/update', data).then(unwrapSchedule),
    remove: (id: number) => makeRequest().post('/api/schedule/jobinfo/remove', null, {params: {id}}).then(unwrapSchedule),
    /** 启动/停止：后端只有触发状态位，没有独立「立即执行」端点。 */
    stop: (id: number) => makeRequest().post('/api/schedule/jobinfo/stop', null, {params: {id}}).then(unwrapSchedule),
    start: (id: number) => makeRequest().post('/api/schedule/jobinfo/start', null, {params: {id}}).then(unwrapSchedule),
};

export const jobLogApi = {
    getList: (params: JobLogQuery = {}) =>
        makeRequest().get('/api/schedule/joblog/list', {params})
            .then(unwrapSchedule)
            .then((arr: any) => (Array.isArray(arr) ? arr.map(camelizeRow) : arr)),
    /** 返回 {content, fromLineNum, toLineNum, isEnd} */
    executionLog: (logId: number, fromLineNum = 0) =>
        makeRequest().get('/api/schedule/joblog/executionLog', {params: {logId, fromLineNum}}).then(unwrapSchedule),
    /** type=0 清空全部；type=1 按 jobId 清理。分权会话只允许 type=1。 */
    clear: (body: {type: 0 | 1; jobId?: number}) => makeRequest().post('/api/schedule/joblog/clear', body).then(unwrapSchedule),
};

export default {jobGroupApi, jobInfoApi, jobLogApi};
