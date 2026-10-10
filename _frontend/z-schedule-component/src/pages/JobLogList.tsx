import React, {useEffect, useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {Button, Descriptions, Drawer, Form, Popconfirm, Select, Space, Table, Tag, Tooltip} from 'antd';
import {ClearOutlined, EyeOutlined, ReloadOutlined} from '@ant-design/icons';
import {jobGroupApi, jobLogApi} from '@/schedule/services/job';
import {EmptyState, ErrorState, PageHeader} from '@/common/components/ui';

/**
 * 调度日志 (JobLog) — 路径: /schedule/log
 *
 * 字段口径以 GET /api/schedule/joblog/list 的实测返回为准（service 层已把蛇形键转驼峰）:
 *   id / jobId / jobGroup / executorAddress / executorHandler / executorParam
 *   triggerTime / triggerCode / triggerMsg / handleTime / handleCode / handleMsg / alarmStatus
 * ⚠️ 行里**没有** triggerStatus，也没有 duration：状态要看 triggerCode + handleCode，
 *   耗时是 handleTime - triggerTime。
 *
 * 后端只有 limit（没有 offset / 没有 total），所以翻页是本地切片：一次取回最近 LIMIT_ROWS 条，
 * 命中上限时在表下明说，不假装那是分页总数。
 *
 * API（全部经 service 封装 → /api/schedule/**，在 SSO 拦截范围内）:
 *   GET  /api/schedule/joblog/list?jobGroup=&jobId=&status=&limit=
 *   GET  /api/schedule/joblog/executionLog?logId=          —— 参数名是 logId，不是 id
 *   POST /api/schedule/joblog/clear   body {type:0 全部|1 按 jobId, jobId?}
 *   GET  /api/schedule/jobgroup/list                      —— 执行器下拉
 */
const LIMIT_ROWS = 200;

/** 后端 status 语义: 1=handle_code 200, 2=handle_code 500, 其余值一律不过滤 */
const STATUS_OPTIONS = [
    {value: 0, label: '全部'},
    {value: 1, label: '执行成功'},
    {value: 2, label: '执行失败'},
];

/** 把 (triggerCode, handleCode) 折成一个可读状态 */
function statusOf(row: any): { color: string; text: string } {
    const tc = Number(row?.triggerCode) || 0;
    const hc = Number(row?.handleCode) || 0;
    if (tc === 0) return {color: 'default', text: '未调度'};
    if (tc !== 200) return {color: 'error', text: '调度失败'};
    if (hc === 200) return {color: 'success', text: '执行成功'};
    if (hc === 0) return {color: 'processing', text: '等待回调'};
    return {color: 'error', text: '执行失败'};
}

const toTs = (v: any): number | null => {
    if (!v) return null;
    const t = new Date(v).getTime();
    return Number.isNaN(t) ? null : t;
};

const fmt = (v: any) => {
    const t = toTs(v);
    return t == null ? '-' : new Date(t).toLocaleString();
};

const JobLogList: React.FC = () => {
    const [logs, setLogs] = useState<any[]>([]);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [groups, setGroups] = useState<any[]>([]);
    const [params, setParams] = useState<any>({jobGroup: 0, status: 0});
    const [detail, setDetail] = useState<any>(null);
    const [searchParams, setSearchParams] = useSearchParams();
    // ?jobId= 由「任务列表 → 查看该任务的调度日志」带过来，后端 /joblog/list 本来就吃这个参数
    const jobId = Number(searchParams.get('jobId')) || 0;

    const loadGroups = async () => {
        try {
            const list = await jobGroupApi.getList();
            setGroups(Array.isArray(list) ? list : []);
        } catch (e: any) {
            // 执行器表可以是真的空 (实测 content: [])，但接口失败也要能看出区别
            console.warn('[调度日志] 执行器列表加载失败:', e?.message || e);
            setGroups([]);
        }
    };

    const load = async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const arr = await jobLogApi.getList({
                jobGroup: params.jobGroup,
                status: params.status,
                jobId,
                limit: LIMIT_ROWS,
            });
            setLogs(Array.isArray(arr) ? arr : []);
        } catch (e: any) {
            setLoadError(e?.message || '加载日志失败');
            setLogs([]);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadGroups();
    }, []);

    useEffect(() => {
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [params.jobGroup, params.status, jobId]);

    const handleView = async (log: any) => {
        setDetail({...log, executionLog: '读取中…'});
        try {
            const res = await jobLogApi.executionLog(log.id);
            setDetail({...log, executionLog: res?.content || '(无执行日志)'});
        } catch (e: any) {
            setDetail({...log, executionLog: `读取失败: ${e?.message || e}`});
        }
    };

    const handleClear = async () => {
        try {
            await jobLogApi.clear({type: 0});
            load();
        } catch (e: any) {
            setLoadError(`清空失败: ${e?.message || e}`);
        }
    };

    const isEmpty = !loading && !loadError && logs.length === 0;

    return (
        <div>
            <PageHeader
                title="调度日志"
                subtitle="查看调度任务执行历史, 支持按执行器和状态过滤"
                breadcrumb={[{label: '调度中心'}, {label: '调度日志'}]}
            />

            {loadError && (
                <div style={{marginBottom: 16}}>
                    <ErrorState error={loadError} onRetry={load} title="加载调度日志失败"/>
                </div>
            )}

            <div style={{
                background: '#ffffff',
                borderRadius: 16,
                padding: 20,
                boxShadow: '0 1px 3px rgba(15, 23, 42, 0.04), 0 1px 2px rgba(15, 23, 42, 0.06)',
            }}>
                {/* 过滤器 + 操作同行 */}
                <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12, marginBottom: 16}}>
                <Form layout="inline">
                    <Form.Item label="执行器">
                        <Select value={params.jobGroup} style={{width: 160}}
                                onChange={v => setParams({...params, jobGroup: v})}>
                            <Select.Option value={0}>全部</Select.Option>
                            {groups.map(g => <Select.Option key={g.id} value={g.id}>{g.title}</Select.Option>)}
                        </Select>
                    </Form.Item>
                    <Form.Item label="状态">
                        <Select value={params.status} style={{width: 120}}
                                onChange={v => setParams({...params, status: v})}>
                            {STATUS_OPTIONS.map(o => (
                                <Select.Option key={o.value} value={o.value}>{o.label}</Select.Option>))}
                        </Select>
                    </Form.Item>
                    {jobId > 0 && (
                        <Form.Item label="任务">
                            <Tag color="blue" closable onClose={() => setSearchParams({})}>
                                仅 #{jobId}
                            </Tag>
                        </Form.Item>
                    )}
                </Form>
                <Space>
                    <Button icon={<ReloadOutlined/>} onClick={load}>刷新</Button>
                    <Popconfirm title="确认清空全部调度日志?"
                                description="后端只支持整表清空 (type=0)，不可恢复"
                                onConfirm={handleClear} okButtonProps={{danger: true}}>
                        <Button danger icon={<ClearOutlined/>}>清空全部</Button>
                    </Popconfirm>
                </Space>
                </div>

                {isEmpty ? (
                    <EmptyState
                        title="暂无调度日志"
                        description="当前过滤条件下没有任何执行记录, 执行器执行任务后会在这里出现"
                    />
                ) : (
                    <Table
                        rowKey="id"
                        dataSource={logs}
                        loading={loading}
                        scroll={{x: 1200}}
                        pagination={{pageSize: 20, showTotal: t => `本次取回 ${t} 条`}}
                        columns={[
                            {title: 'ID', dataIndex: 'id', width: 90},
                            {title: '任务ID', dataIndex: 'jobId', width: 90},
                            {
                                title: '执行器', dataIndex: 'jobGroup', width: 130,
                                render: (id: any) => groups.find(g => g.id === id)?.title || `#${id}`,
                            },
                            {
                                title: 'Handler', dataIndex: 'executorHandler', width: 160,
                                render: (v: any) => v || '-',
                            },
                            {
                                title: '状态', width: 110,
                                render: (_: any, r: any) => {
                                    const s = statusOf(r);
                                    return (
                                        <Tooltip title={`trigger_code=${r.triggerCode} handle_code=${r.handleCode}`}>
                                            <Tag color={s.color}>{s.text}</Tag>
                                        </Tooltip>
                                    );
                                },
                            },
                            {
                                title: '调度时间', dataIndex: 'triggerTime', width: 180,
                                render: (t: any) => fmt(t),
                            },
                            {
                                title: '耗时', width: 100,
                                render: (_: any, r: any) => {
                                    const a = toTs(r.triggerTime);
                                    const b = toTs(r.handleTime);
                                    return a != null && b != null && b >= a ? `${b - a}ms` : '-';
                                },
                            },
                            {
                                title: '操作', width: 90, fixed: 'right' as const,
                                render: (_: any, r: any) => (
                                    <Tooltip title="查看执行详情">
                                        <Button type="link" size="small" icon={<EyeOutlined/>}
                                                onClick={() => handleView(r)}>详情</Button>
                                    </Tooltip>
                                ),
                            },
                        ]}
                    />
                )}
                {!isEmpty && logs.length >= LIMIT_ROWS && (
                    <div style={{marginTop: 8, fontSize: 12, color: '#8c8c8c'}}>
                        仅取回最近 {LIMIT_ROWS} 条（后端 /api/schedule/joblog/list 只有 limit，无 offset/total），
                        要找更早的记录请缩小过滤条件。
                    </div>
                )}
            </div>

            <Drawer title={`执行记录 #${detail?.id ?? ''}`} width={720}
                    open={!!detail} onClose={() => setDetail(null)}>
                {detail && (
                    <>
                        <Descriptions column={1} size="small" bordered>
                            <Descriptions.Item label="任务 ID">{detail.jobId}</Descriptions.Item>
                            <Descriptions.Item label="Handler">{detail.executorHandler || '-'}</Descriptions.Item>
                            <Descriptions.Item label="执行地址">{detail.executorAddress || '-'}</Descriptions.Item>
                            <Descriptions.Item label="调度时间">{fmt(detail.triggerTime)}</Descriptions.Item>
                            <Descriptions.Item label="回调时间">{fmt(detail.handleTime)}</Descriptions.Item>
                            <Descriptions.Item label="调度信息">{detail.triggerMsg || '-'}</Descriptions.Item>
                            <Descriptions.Item label="执行结果">{statusOf(detail).text}</Descriptions.Item>
                        </Descriptions>
                        <div style={{marginTop: 16, fontWeight: 600}}>执行日志</div>
                        <pre style={{
                            marginTop: 8,
                            padding: 12,
                            background: '#f6f8fa',
                            borderRadius: 8,
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-all',
                            fontSize: 12,
                            lineHeight: 1.6,
                        }}>{detail.executionLog}</pre>
                    </>
                )}
            </Drawer>
        </div>
    );
};

export default JobLogList;
