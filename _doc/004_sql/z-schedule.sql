-- ============================================================
-- z-schedule 调度中心建表脚本
--
-- 与 z-schedule-spring-boot-starter 的 6 个 DO 一一对应：
--   JobInfoDO / JobLogDO / JobGroupDO / JobRegistryDO / JobLeaderDO / UserDO
--
-- 幂等：全部 CREATE TABLE IF NOT EXISTS + INSERT IGNORE，**不含任何 DROP**。
-- 可以直接在已有库上重复执行，不会破坏数据。
--
-- 本文件是构建门禁的一部分：ShippedSchemaH2Test 会在 H2(MySQL 模式) 上**原样执行本文件**，
-- 然后用真实的 MyBatis-Plus mapper 对 6 张表做读写。DO 加一列而这里漏一列，构建就红——
-- 之前正是缺这一层，"Unknown column 'trigger_type'" 这类问题只在线上首次访问时现形。
--
-- 执行方式：手工在目标库执行，或由运维脚本接入。z-schedule 不会自动建表。
-- ============================================================

-- 1. 任务定义表
CREATE TABLE IF NOT EXISTS `z_schedule_job_info`
(
    `id`                       int           NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `job_group`                int           NOT NULL DEFAULT 0 COMMENT '执行器主键ID',
    `job_desc`                 varchar(255)  NOT NULL COMMENT '任务描述',
    `job_cron`                 varchar(128)  NOT NULL COMMENT 'Cron表达式',
    `author`                   varchar(64)   DEFAULT NULL COMMENT '作者',
    `alarm_email`              varchar(255)  DEFAULT NULL COMMENT '报警邮件',
    `executor_route_strategy`  varchar(64)   DEFAULT NULL COMMENT '路由策略',
    `executor_handler`         varchar(255)  DEFAULT NULL COMMENT '执行器Handler名称',
    `executor_param`           varchar(512)  DEFAULT NULL COMMENT '执行参数',
    `executor_block_strategy`  varchar(64)   DEFAULT NULL COMMENT '阻塞策略',
    `executor_timeout`         int           NOT NULL DEFAULT 0 COMMENT '超时时间(秒), 0=不限',
    `executor_fail_retry_count` int          NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    `trigger_status`           tinyint       NOT NULL DEFAULT 0 COMMENT '0-停止 1-运行',
    `trigger_last_time`        bigint        NOT NULL DEFAULT 0 COMMENT '上次触发时间(ms)',
    `trigger_next_time`        bigint        NOT NULL DEFAULT 0 COMMENT '下次触发时间(ms)',
    `trigger_type`             varchar(8)    NOT NULL DEFAULT 'CRON' COMMENT '触发类型: CRON / FIX_RATE / FIX_DELAY',
    `fix_interval`             bigint        NOT NULL DEFAULT 0 COMMENT 'FIX_RATE/FIX_DELAY 间隔(毫秒), 0=未配置',
    `misfire_strategy`         varchar(32)   NOT NULL DEFAULT 'DO_NOTHING' COMMENT '调度过期策略: DO_NOTHING / FIRE_ONCE_NOW',
    `child_job_id`             varchar(256)  NOT NULL DEFAULT '' COMMENT '子任务ID(逗号分隔)',
    `add_time`                 datetime      DEFAULT NULL COMMENT '添加时间',
    `update_time`              datetime      DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_job_group` (`job_group`),
    KEY `idx_trigger_status` (`trigger_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='z-schedule 任务定义表';

-- 2. 任务执行日志表
CREATE TABLE IF NOT EXISTS `z_schedule_job_log`
(
    `id`                       bigint        NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `job_group`                int           NOT NULL DEFAULT 0 COMMENT '执行器主键ID',
    `job_id`                   int           NOT NULL COMMENT '任务ID',
    `executor_address`         varchar(255)  DEFAULT NULL COMMENT '执行器地址',
    `executor_handler`         varchar(255)  DEFAULT NULL COMMENT 'Handler',
    `executor_param`           varchar(512)  DEFAULT NULL COMMENT '执行参数',
    `executor_sharding_param`  varchar(64)   DEFAULT NULL COMMENT '分片参数 1/2',
    `executor_fail_retry_count` int          NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    `trigger_time`             datetime      DEFAULT NULL COMMENT '调度时间',
    `trigger_code`             int           NOT NULL DEFAULT 0 COMMENT '调度结果',
    `trigger_msg`              varchar(512)  DEFAULT NULL COMMENT '调度日志',
    `handle_time`              datetime      DEFAULT NULL COMMENT '执行时间',
    `handle_code`              int           NOT NULL DEFAULT 0 COMMENT '执行结果',
    `handle_msg`               varchar(2048) DEFAULT NULL COMMENT '执行日志',
    `alarm_status`             tinyint       NOT NULL DEFAULT 0 COMMENT '0-默认 1-无需告警 2-告警成功 3-告警失败',
    PRIMARY KEY (`id`),
    KEY `idx_job_id` (`job_id`),
    KEY `idx_trigger_time` (`trigger_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='z-schedule 任务日志表';

-- 3. 执行器分组表
CREATE TABLE IF NOT EXISTS `z_schedule_job_group`
(
    `id`           int           NOT NULL AUTO_INCREMENT COMMENT '分组ID',
    `app_name`     varchar(64)   NOT NULL COMMENT '执行器AppName',
    `title`        varchar(128)  NOT NULL COMMENT '执行器名称',
    `order_num`    int           NOT NULL DEFAULT 0 COMMENT '排序',
    `address_type` tinyint       NOT NULL DEFAULT 0 COMMENT '0-自动注册 1-手动录入',
    `address_list` varchar(2048) DEFAULT NULL COMMENT '执行器地址列表(逗号分隔)',
    `update_time`  datetime      DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_name` (`app_name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='z-schedule 执行器分组表';

-- 4. 执行器心跳注册表
CREATE TABLE IF NOT EXISTS `z_schedule_job_registry`
(
    `id`             int          NOT NULL AUTO_INCREMENT COMMENT '注册ID',
    `registry_group` varchar(64)  NOT NULL DEFAULT 'EXECUTOR' COMMENT '注册分组',
    `registry_key`   varchar(255) NOT NULL COMMENT '执行器AppName',
    `registry_value` varchar(255) NOT NULL COMMENT '执行器地址',
    `update_time`    datetime     NOT NULL COMMENT '心跳时间',
    PRIMARY KEY (`id`),
    KEY `idx_registry_key` (`registry_key`),
    KEY `idx_update_time` (`update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='z-schedule 执行器心跳注册表';

-- 5. 集群 Leader 选举表（单行，DB 行锁抢占）
CREATE TABLE IF NOT EXISTS `z_schedule_job_leader`
(
    `id`          int          NOT NULL COMMENT '固定为1, 单行锁',
    `owner`       varchar(64)  DEFAULT NULL COMMENT 'Leader 实例 ID (UUID)',
    `host`        varchar(64)  DEFAULT NULL COMMENT 'Leader 主机',
    `expire_time` datetime     DEFAULT NULL COMMENT 'Leader 过期时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='z-schedule 集群 Leader 选举表';

-- 初始化 Leader 行 (id=1)。缺行时选主会静默停摆，
-- LeaderElector.@PostConstruct 也会补建这一行；这里是首次部署的正路。
INSERT IGNORE INTO `z_schedule_job_leader` (`id`, `owner`, `host`, `expire_time`)
VALUES (1, NULL, NULL, NULL);

-- 6. 调度中心用户表
-- username 上的唯一键是服务层"先查再插"之外的最终裁决者：
-- UserServiceImpl 依赖它拦并发重名，删掉它等于把账号唯一性交给竞态。
CREATE TABLE IF NOT EXISTS `z_schedule_user`
(
    `id`          int          NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`    varchar(64)  NOT NULL DEFAULT '' COMMENT '用户名',
    `password`    varchar(128) NOT NULL DEFAULT '' COMMENT '密码(MD5, 按 UTF-8 取字节)',
    `role`        varchar(32)  NOT NULL DEFAULT '' COMMENT '角色: ADMIN / NORMAL',
    `permission`  varchar(512) NOT NULL DEFAULT '' COMMENT '权限: 逗号分隔的 jobGroup id',
    `add_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='z-schedule 用户表';

-- ============================================================
-- 与历史脚本的差异（2026-09-26 对齐）
--
-- z-schedule 此前**不附带任何建表脚本**，唯一在用的参照是 z-opc 仓的
-- _doc/004_sql/z-schedule.sql 与 _doc/006_troubleshooting/z-schedule-schema-migration.sql。
-- 逐列比对 DO 与那份 004 脚本的结果：
--   z_schedule_job_info 少 4 列 —— trigger_type / fix_interval / misfire_strategy / child_job_id
--       (006 迁移脚本补了其中 3 列，仍漏 fix_interval；MySQL 也不支持 ADD COLUMN IF NOT EXISTS)
--   z_schedule_user 整张表缺失（只在 006 迁移脚本里，且它插入的 admin 口令是明文，
--       而登录按 MD5 比对列值 ⇒ 那个种子账号登不进去，需要把列值改成散列才能用）
-- 其余 4 张表列集一致。
--
-- 本文件把六个 DO 需要的列补齐，之后新增列请同时改 DO 与本文件——
-- ShippedSchemaH2Test 会对不一致的改动直接判红。
-- ============================================================
