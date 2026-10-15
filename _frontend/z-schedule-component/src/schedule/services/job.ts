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

import type {JobGroup, JobLog, JobLogQuery} from '../types';

let _baseURL = '';

export function configureScheduleBaseURL(baseURL: string) {
    _baseURL = baseURL || '';
}

function makeRequest() {
    return createRequest({baseURL: _baseURL, timeout: 15000});
}

export const jobGroupApi = {
    getList: () => makeRequest().get('/api/schedule/jobgroup/list'),
    get: (id: number) => makeRequest().get('/api/schedule/jobgroup/get', {params: {id}}),
    registryNodes: (appName: string) =>
        makeRequest().get('/api/schedule/jobgroup/registryNodes', {params: {appName}}),
    add: (data: Partial<JobGroup>) => makeRequest().post('/api/schedule/jobgroup/add', data),
    update: (data: Partial<JobGroup>) => makeRequest().post('/api/schedule/jobgroup/update', data),
    remove: (id: number) => makeRequest().post('/api/schedule/jobgroup/remove', null, {params: {id}}),
};

export const jobLogApi = {
    getList: (params: JobLogQuery = {}) => makeRequest().get('/api/schedule/joblog/list', {params}),
    /** 返回 {content, fromLineNum, toLineNum, isEnd} */
    executionLog: (logId: number, fromLineNum = 0) =>
        makeRequest().get('/api/schedule/joblog/executionLog', {params: {logId, fromLineNum}}),
    /** type=0 清空全部；type=1 按 jobId 清理。分权会话只允许 type=1。 */
    clear: (body: {type: 0 | 1; jobId?: number}) => makeRequest().post('/api/schedule/joblog/clear', body),
};

export default {jobGroupApi, jobLogApi};
