import React, {useEffect, useState} from 'react';
import {Button, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Tag} from 'antd';
import {
    PlusOutlined,
    ReloadOutlined,
    ScheduleOutlined,
} from '@ant-design/icons';
import {jobGroupApi, jobInfoApi} from '@/schedule/services/job';
import {ErrorState, ListTable} from '@yuku123/z-frontend-common/ui';

/**
 * 任务台账 (JobInfo) — 路径: /schedule/jobs
 *
 * 后端：GET /api/schedule/jobinfo/list|get、POST add|update|remove|stop|start
 *
 * ⚠ 键名口径：本接口是 DO 直出 ⇒ **蛇形**（job_cron / job_desc / job_group /
 *   trigger_status / trigger_next_time）。同一模块的 jobgroup 却走 Map 直出（驼峰），
 *   两者不能混用 —— 按驼峰读这里会整列 "-"（2026-11-09 之前本页是个收 props 的骨架，
 *   ScheduleApp 传 jobs={[]} 所以永远「暂无任务」，真实数据一条都没接出来）。
 *
 * ⚠ trigger_next_time / trigger_last_time 是**毫秒时间戳数字**，不是 ISO 串。
 */
const JobList: React.FC = () => {
    const [jobs, setJobs] = useState<any[]>([]);
    const [groups, setGroups] = useState<any[]>([]);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [modalVisible, setModalVisible] = useState(false);
    const [editing, setEditing] = useState<any>(null);
    const [form] = Form.useForm();

    const groupName = (id?: number) => {
        const g = groups.find((x) => x.id === id);
        return g?.title || g?.appName || (id != null ? `#${id}` : '-');
    };

    const fmtTime = (t?: number | string) => {
        if (t == null || t === 0) return '-';
        const ms = typeof t === 'number' ? t : Date.parse(String(t));
        if (!Number.isFinite(ms)) return String(t);
        return new Date(ms).toLocaleString();
    };

    const load = async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const [list, gs] = await Promise.all([
                jobInfoApi.getList(),
                jobGroupApi.getList().catch(() => []),
            ]);
            setJobs(Array.isArray(list) ? list : []);
            setGroups(Array.isArray(gs) ? gs : []);
        } catch (e: any) {
            setLoadError(e?.message || String(e));
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        load();
    }, []);

    const handleAdd = () => {
        setEditing(null);
        form.resetFields();
        form.setFieldsValue({
            trigger_type: 'CRON',
            job_cron: '0 0/5 * * * ?',
            executor_route_strategy: 'FIRST',
            executor_block_strategy: 'SERIAL_EXECUTION',
            executor_fail_retry_count: 0,
            misfire_strategy: 'DO_NOTHING',
        });
        setModalVisible(true);
    };

    const handleEdit = (j: any) => {
        setEditing(j);
        form.setFieldsValue(j);
        setModalVisible(true);
    };

    const handleSubmit = async () => {
        try {
            const values = await form.validateFields();
            if (editing) {
                await jobInfoApi.update({...values, id: editing.id});
                message.success('更新成功');
            } else {
                await jobInfoApi.add(values);
                message.success('新增成功');
            }
            setModalVisible(false);
            load();
        } catch (e: any) {
            if (e?.errorFields) return;
            message.error('操作失败: ' + (e?.message || e));
        }
    };

    const handleDelete = async (j: any) => {
        try {
            await jobInfoApi.remove(j.id);
            message.success('已删除');
            load();
        } catch (e: any) {
            message.error('删除失败: ' + (e?.message || e));
        }
    };

    const toggle = async (j: any) => {
        try {
            if (j.trigger_status === 1) {
                await jobInfoApi.stop(j.id);
                message.success('已停止');
            } else {
                await jobInfoApi.start(j.id);
                message.success('已启动');
            }
            load();
        } catch (e: any) {
            message.error('操作失败: ' + (e?.message || e));
        }
    };

    return (
        <div style={{padding: 16}}>
            {loadError && (
                <div style={{marginBottom: 16}}>
                    <ErrorState error={loadError} onRetry={load} title="加载任务失败"/>
                </div>
            )}
            <ListTable
                cardTitle={<Space><ScheduleOutlined/> 任务列表</Space>}
                rows={jobs}
                rowKey="id"
                loading={loading}
                searchKeys="job_desc,executor_handler,job_cron,author"
                searchPlaceholder="按描述 / Handler / cron / 负责人过滤"
                cardExtra={
                    <Space>
                        <Button icon={<ReloadOutlined/>} onClick={load}>刷新</Button>
                        <Button type="primary" icon={<PlusOutlined/>} onClick={handleAdd}>新增任务</Button>
                    </Space>
                }
                emptyTitle="还没有调度任务"
                emptyDescription="「新增任务」建第一个；任务挂在执行器下，执行器进程起来后会调 POST /executor/beat 自动注册"
                columns={[
                    {title: 'ID', dataIndex: 'id', width: 60},
                    {
                        title: '任务描述', dataIndex: 'job_desc', width: 220,
                        render: (t, r) => (
                            <Space direction="vertical" size={0}>
                                <span>{t || '-'}</span>
                                <code style={{fontSize: 11, color: '#888'}}>{r.executor_handler || '-'}</code>
                            </Space>
                        ),
                    },
                    {
                        title: '执行器', dataIndex: 'job_group', width: 140,
                        render: (t) => <Tag color="blue">{groupName(t)}</Tag>,
                    },
                    {
                        title: '调度规则', dataIndex: 'job_cron', width: 170,
                        render: (t, r) => (
                            <Space direction="vertical" size={0}>
                                <code style={{fontSize: 11}}>{t || '-'}</code>
                                <span style={{fontSize: 11, color: '#888'}}>{r.trigger_type || ''}</span>
                            </Space>
                        ),
                    },
                    {
                        title: '状态', dataIndex: 'trigger_status', width: 90,
                        render: (t) => t === 1
                            ? <Tag color="green">运行中</Tag>
                            : <Tag color="default">已停止</Tag>,
                    },
                    {
                        title: '下次触发', dataIndex: 'trigger_next_time', width: 170,
                        render: (t) => fmtTime(t),
                    },
                    {
                        title: '负责人', dataIndex: 'author', width: 90,
                        render: (t) => t || '-',
                    },
                    {
                        title: '操作', width: 210, fixed: 'right',
                        render: (_, j) => (
                            <Space>
                                <Button type="link" size="small"
                                        onClick={() => toggle(j)}>
                                    {j.trigger_status === 1 ? '停止' : '启动'}
                                </Button>
                                <Button type="link" size="small" onClick={() => handleEdit(j)}>编辑</Button>
                                <Popconfirm title="确认删除该任务?" onConfirm={() => handleDelete(j)}>
                                    <Button type="link" size="small" danger>删除</Button>
                                </Popconfirm>
                            </Space>
                        ),
                    },
                ]}
            />

            <Modal
                title={editing ? '编辑任务' : '新增任务'}
                open={modalVisible}
                onCancel={() => setModalVisible(false)}
                onOk={handleSubmit}
                width={720}
                destroyOnHidden
            >
                <Form form={form} layout="vertical">
                    <Space size="large" wrap>
                        <Form.Item name="job_group" label="执行器"
                                   rules={[{required: true, message: '必选'}]}>
                            <Select style={{width: 240}}
                                    placeholder="选择执行器"
                                    options={groups.map((g) => ({
                                        value: g.id,
                                        label: g.title || g.appName || `#${g.id}`,
                                    }))}/>
                        </Form.Item>
                        <Form.Item name="executor_handler" label="Handler"
                                   rules={[{required: true, message: '必填'}]}>
                            <Input style={{width: 240}} placeholder="如: demoJobHandler"/>
                        </Form.Item>
                        <Form.Item name="author" label="负责人">
                            <Input style={{width: 160}} placeholder="如: e2e"/>
                        </Form.Item>
                    </Space>
                    <Space size="large" wrap>
                        <Form.Item name="trigger_type" label="调度类型">
                            <Select style={{width: 160}} options={[
                                {value: 'CRON', label: 'CRON'},
                                {value: 'FIX_RATE', label: '固定频率'},
                            ]}/>
                        </Form.Item>
                        <Form.Item name="job_cron" label="cron"
                                   rules={[{required: true, message: '必填'}]}>
                            <Input style={{width: 200}} placeholder="0 0/5 * * * ?"/>
                        </Form.Item>
                        <Form.Item name="executor_fail_retry_count" label="失败重试">
                            <InputNumber min={0} style={{width: 120}}/>
                        </Form.Item>
                    </Space>
                    <Form.Item name="job_desc" label="任务描述"
                               rules={[{required: true, message: '必填'}]}>
                        <Input placeholder="一句话说清这个任务干什么"/>
                    </Form.Item>
                    <Form.Item name="executor_param" label="执行参数">
                        <Input.TextArea rows={2} placeholder="传给 Handler 的参数（可空）"/>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
};

export default JobList;
