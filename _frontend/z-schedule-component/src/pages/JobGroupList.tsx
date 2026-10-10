import React, {useEffect, useState} from 'react';
import {Button, Card, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Tag} from 'antd';
import {ClusterOutlined, DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined} from '@ant-design/icons';
import {jobGroupApi} from '@/schedule/services/job';
import {AddressType} from '@/schedule/types';
import {EmptyState, ErrorState} from '@/common/components/ui';

/**
 * 执行器管理 (JobGroup) — 路径: /schedule/group
 *
 * 键名口径: /api/schedule/jobgroup/list 是 Map 直出 (JobGroupServiceImpl.toFrontendList)，
 * 所以 appName / addressType / addressList / registryList 本来就是驼峰，不走蛇形转换。
 *
 * API（service 层封装，全部在 SSO 拦截范围内）:
 *   - GET  /api/schedule/jobgroup/list           全量执行器
 *   - GET  /api/schedule/jobgroup/registryNodes  在线注册节点 (按 appName)
 *   - POST /api/schedule/jobgroup/add|update     body 驼峰→蛇形由 service 处理
 *   - POST /api/schedule/jobgroup/remove?id=
 */
const JobGroupList: React.FC = () => {
    const [groups, setGroups] = useState<any[]>([]);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [modalVisible, setModalVisible] = useState(false);
    const [editing, setEditing] = useState<any>(null);
    const [form] = Form.useForm();

    const load = async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const list = await jobGroupApi.getList();
            setGroups(Array.isArray(list) ? list : []);
        } catch (e: any) {
            setLoadError(e?.message || '加载执行器失败');
            setGroups([]);
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
        form.setFieldsValue({addressType: AddressType.AUTO, order: 1});
        setModalVisible(true);
    };

    const handleEdit = (g: any) => {
        setEditing(g);
        form.setFieldsValue(g);
        setModalVisible(true);
    };

    const handleSubmit = async () => {
        try {
            const values = await form.validateFields();
            if (editing) {
                await jobGroupApi.update({...values, id: editing.id});
                message.success('更新成功');
            } else {
                await jobGroupApi.add(values);
                message.success('新增成功');
            }
            setModalVisible(false);
            load();
        } catch (e: any) {
            if (e?.errorFields) return;
            message.error('操作失败: ' + (e?.message || e));
        }
    };

    const handleDelete = async (g: any) => {
        try {
            await jobGroupApi.remove(g.id);
            message.success('已删除');
            load();
        } catch (e: any) {
            message.error('删除失败: ' + (e?.message || e));
        }
    };

    return (
        <div style={{padding: 16}}>
            {loadError && (
                <div style={{marginBottom: 16}}>
                    <ErrorState error={loadError} onRetry={load} title="加载执行器失败"/>
                </div>
            )}
            <Card
                title={<Space><ClusterOutlined/> 执行器管理</Space>}
                extra={
                    <Space>
                        <Button icon={<ReloadOutlined/>} onClick={load}>刷新</Button>
                        <Button type="primary" icon={<PlusOutlined/>} onClick={handleAdd}>新增执行器</Button>
                    </Space>
                }
            >
                <Table
                    rowKey="id"
                    dataSource={groups}
                    loading={loading}
                    pagination={{pageSize: 20, size: 'small'}}
                    locale={{
                        emptyText: !loading && !loadError ? (
                            <EmptyState
                                title="还没有执行器"
                                description="执行器进程起来后会调 POST /executor/beat 自动注册；也可以「新增执行器」手动录入机器地址"
                            />
                        ) : undefined
                    }}
                    columns={[
                        {title: 'ID', dataIndex: 'id', width: 60},
                        {
                            title: 'AppName', dataIndex: 'appName', width: 200,
                            render: t => <code>{t}</code>
                        },
                        {title: '标题', dataIndex: 'title', width: 180},
                        {
                            title: '注册方式', dataIndex: 'addressType', width: 100,
                            render: t => t === 0 ? <Tag color="blue">自动注册</Tag> :
                                t === 1 ? <Tag color="orange">手动录入</Tag> : '-'
                        },
                        {
                            title: '机器地址', dataIndex: 'addressList', width: 280,
                            render: t => t ? <code style={{fontSize: 11}}>{t}</code> : <Tag>未注册</Tag>
                        },
                        {
                            title: '在线节点', dataIndex: 'registryList', width: 120,
                            // toFrontendList 按 addressList 拆出来的，不额外查注册中心
                            render: (t: any) => `${(t || []).length} 个`
                        },
                        {title: '排序', dataIndex: 'order', width: 70},
                        {
                            title: '更新时间', dataIndex: 'updateTime', width: 180,
                            render: t => t ? new Date(t).toLocaleString() : '-'
                        },
                        {
                            title: '操作', width: 140, fixed: 'right',
                            render: (_, g) => (
                                <Space>
                                    <Button size="small" icon={<EditOutlined/>}
                                            onClick={() => handleEdit(g)}>编辑</Button>
                                    <Popconfirm title="确认删除该执行器?" onConfirm={() => handleDelete(g)}>
                                        <Button size="small" danger icon={<DeleteOutlined/>}>删除</Button>
                                    </Popconfirm>
                                </Space>
                            )
                        },
                    ]}
                />
            </Card>

            <Modal
                title={editing ? '编辑执行器' : '新增执行器'}
                open={modalVisible}
                onCancel={() => setModalVisible(false)}
                onOk={handleSubmit}
                destroyOnHidden
            >
                <Form form={form} layout="vertical">
                    <Form.Item name="appName" label="AppName"
                               rules={[{required: true, message: '必填'},
                                   {pattern: /^[a-zA-Z][a-zA-Z0-9_-]*$/, message: '英文+数字+下划线'}]}>
                        <Input placeholder="如: z-schedule-executor"/>
                    </Form.Item>
                    <Form.Item name="title" label="标题"
                               rules={[{required: true, message: '必填'}]}>
                        <Input placeholder="如: 示例执行器"/>
                    </Form.Item>
                    {/* addressType / order 后端是 int，原来用 <Input> 收字符串，改成控件 */}
                    <Form.Item name="addressType" label="注册方式"
                               rules={[{required: true}]}>
                        <Select options={[
                            {value: AddressType.AUTO, label: '自动注册 (执行器 /executor/beat 上报)'},
                            {value: AddressType.MANUAL, label: '手动录入机器地址'},
                        ]}/>
                    </Form.Item>
                    <Form.Item name="addressList" label="机器地址 (手动录入时填, 多地址逗号分隔)">
                        <Input.TextArea rows={2} placeholder="如: 192.168.1.1:9999,192.168.1.2:9999"/>
                    </Form.Item>
                    <Form.Item name="order" label="排序">
                        <InputNumber min={0} style={{width: '100%'}} placeholder="数字越小越靠前"/>
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
};

export default JobGroupList;