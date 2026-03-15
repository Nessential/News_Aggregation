CREATE DATABASE IF NOT EXISTS `news_aggregation`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `nacos_config`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;


create table agent_execution_effect_latch
(
    id                   bigint auto_increment
        primary key,
    effect_key           varchar(256)                         not null comment '副作用幂等键',
    run_id               varchar(64)                          not null comment '运行ID',
    step_id              varchar(128)                         not null comment '步骤ID',
    status               varchar(32)                          not null comment '副作用状态',
    provider_trace       varchar(256)                         null comment '外部系统追踪ID',
    request_payload_hash varchar(128)                         null comment '请求摘要',
    response_digest      varchar(256)                         null comment '响应摘要',
    error_code           varchar(128)                         null comment '错误码',
    error_message        varchar(1000)                        null comment '错误信息',
    deleted              tinyint(1) default 0                 not null comment '逻辑删除',
    lock_version         int        default 0                 not null comment '乐观锁版本',
    gmt_create           datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified         datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    constraint uk_effect_key
        unique (effect_key)
)
    comment 'Plan-Executor副作用门闩';

create index idx_effect_latch_run_step
    on agent_execution_effect_latch (run_id, step_id);

create table agent_execution_event_log
(
    id            bigint auto_increment
        primary key,
    run_id        varchar(64)                          not null comment '运行ID',
    step_id       varchar(128)                         null comment '步骤ID',
    event_type    varchar(64)                          not null comment '事件类型',
    event_version int        default 1                 not null comment '事件版本',
    from_state    varchar(32)                          null comment '来源状态',
    to_state      varchar(32)                          null comment '目标状态',
    reason_code   varchar(128)                         null comment '原因码',
    message       varchar(1000)                        null comment '事件说明',
    payload_json  longtext                             null comment '事件载荷',
    deleted       tinyint(1) default 0                 not null comment '逻辑删除',
    lock_version  int        default 0                 not null comment '乐观锁版本',
    gmt_create    datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified  datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间'
)
    comment 'Plan-Executor事件审计日志';

create index idx_event_log_run_created
    on agent_execution_event_log (run_id, gmt_create);

create index idx_event_log_step_created
    on agent_execution_event_log (step_id, gmt_create);

create table agent_execution_run
(
    id                  bigint auto_increment
        primary key,
    run_id              varchar(64)                           not null comment '执行运行ID',
    tenant_id           varchar(64) default 'default'         not null comment '租户ID',
    session_id          varchar(64)                           not null comment '会话ID',
    turn_id             varchar(64)                           not null comment '轮次ID',
    request_dedupe_key  varchar(256)                          not null comment '请求去重键',
    plan_hash           varchar(128)                          not null comment '计划哈希',
    plan_id             varchar(128)                          null comment 'Planner输出计划ID',
    active_plan_version int         default 1                 not null comment '当前激活计划版本',
    replan_count_run    int         default 0                 not null comment 'run级重规划计数',
    status              varchar(32)                           not null comment '运行状态',
    current_step        varchar(128)                          null comment '当前步骤ID',
    error_code          varchar(128)                          null comment '错误码',
    error_message       varchar(1000)                         null comment '错误信息',
    started_at          datetime                              not null comment '开始时间',
    finished_at         datetime                              null comment '结束时间',
    deleted             tinyint(1)  default 0                 not null comment '逻辑删除',
    lock_version        int         default 0                 not null comment '乐观锁版本',
    gmt_create          datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified        datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    constraint uk_request_dedupe_key
        unique (request_dedupe_key),
    constraint uk_run_id
        unique (run_id)
)
    comment 'Plan-Executor运行主记录';

create index idx_execution_run_plan_hash
    on agent_execution_run (plan_hash);

create index idx_execution_run_session_turn
    on agent_execution_run (session_id, turn_id);

create index idx_execution_run_tenant_session
    on agent_execution_run (tenant_id, session_id);

create index idx_run_active_plan_version
    on agent_execution_run (run_id, active_plan_version);

create table agent_execution_step_run
(
    id                         bigint auto_increment
        primary key,
    run_id                     varchar(64)                          not null comment '运行ID',
    step_id                    varchar(128)                         not null comment '步骤ID',
    plan_version               int        default 1                 not null comment 'step所属计划版本',
    capability_name            varchar(128)                         null comment '规划工具名',
    active_capability_name     varchar(128)                         null comment '当前生效工具名',
    selected_tool              varchar(128)                         null comment '本次执行选用的工具',
    selection_reason_code      varchar(128)                         null comment '工具选择原因码',
    circuit_state_snapshot     varchar(1000)                        null comment '熔断器状态快照JSON',
    fallback_candidates_json   text                                 null comment 'Fallback候选工具快照JSON',
    status                     varchar(32)                          not null comment '步骤状态',
    attempt                    int        default 0                 not null comment '执行次数',
    max_retries                int        default 0                 not null comment '最大重试次数',
    recovery_attempt           int        default 0                 not null comment '恢复尝试次数',
    max_recovery_attempts      int        default 0                 not null comment '最大恢复尝试次数',
    worker_id                  varchar(128)                         null comment '执行Worker',
    lease_until                datetime                             null comment '租约截止时间',
    depends_on_json            text                                 null comment '依赖步骤JSON',
    input_json                 longtext                             null comment '输入快照JSON',
    output_json                longtext                             null comment '输出快照JSON',
    side_effect                varchar(32)                          null comment '副作用类型',
    fallback_tools_json        text                                 null comment 'Fallback工具集合',
    replan_allowed             tinyint(1)                           null comment '失败后是否允许重规划',
    need_user_input_on_failure tinyint(1)                           null comment '失败后是否需要用户补充输入',
    resume_mode                varchar(32)                          null comment '恢复模式',
    replan_count_step          int        default 0                 not null comment 'step级重规划计数',
    last_replan_reason_code    varchar(128)                         null comment '最近一次重规划原因码',
    change_proof_snapshot      text                                 null comment '重规划变化证明快照',
    evidence_snapshot          text                                 null comment '重规划证据快照',
    replan_decision_action     varchar(32)                          null comment '最近一次重规划决策动作',
    reason_code                varchar(128)                         null comment '原因码',
    error_code                 varchar(128)                         null comment '错误码',
    error_message              varchar(1000)                        null comment '错误信息',
    started_at                 datetime                             null comment '开始时间',
    finished_at                datetime                             null comment '结束时间',
    deleted                    tinyint(1) default 0                 not null comment '逻辑删除',
    lock_version               int        default 0                 not null comment '乐观锁版本',
    gmt_create                 datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified               datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    constraint uk_run_step
        unique (run_id, step_id)
)
    comment 'Plan-Executor步骤运行记录';

create index idx_step_plan_status
    on agent_execution_step_run (run_id, plan_version, status);

create index idx_step_run_run_status
    on agent_execution_step_run (run_id, status);

create index idx_step_run_status_lease
    on agent_execution_step_run (status, lease_until);

create table agent_tool_circuit_state
(
    id                bigint auto_increment
        primary key,
    tool_name         varchar(128)                         not null comment '工具名',
    capability        varchar(128)                         not null comment '能力名',
    state             varchar(32)                          not null comment 'CLOSED/OPEN/HALF_OPEN',
    open_until        datetime                             null comment '熔断冷却截止时间',
    half_open_owner   varchar(128)                         null comment '半开探针持有者',
    owner_lease_until datetime                             null comment '持有者租约截止时间',
    last_reason_code  varchar(128)                         null comment '最近一次状态变迁原因码',
    deleted           tinyint(1) default 0                 not null comment '逻辑删除',
    lock_version      int        default 0                 not null comment '乐观锁版本',
    gmt_create        datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified      datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '修改时间',
    constraint uk_tool_capability
        unique (tool_name, capability)
)
    comment '工具熔断器状态表';

create table chat_message
(
    message_id       bigint                                not null comment '消息唯一ID（雪花算法）'
        primary key,
    turn_id          varchar(64)                           not null comment '对话轮次ID',
    session_id       varchar(64)                           not null comment '会话ID',
    request_hash     varchar(64)                           null comment '请求哈希（关联幂等系统）',
    role             tinyint                               not null comment '角色: 0=用户, 1=系统',
    content          text                                  null comment '消息内容',
    status           tinyint     default 1                 not null comment '状态: 0=处理中, 1=成功, 2=失败',
    seq_no           int         default 0                 not null comment '消息序号（用于排序）',
    created_at       datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updated_at       datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    user_id          varchar(50)                           null,
    content_markdown text                                  null comment 'markdown snapshot for rendering',
    content_format   varchar(16) default 'PLAIN'           not null comment 'content format: PLAIN/MARKDOWN'
)
    comment '对话消息表' collate = utf8mb4_unicode_ci;

create index idx_chat_message_user_id
    on chat_message (user_id);

create index idx_chat_message_user_session
    on chat_message (user_id, session_id);

create index idx_message_request_hash
    on chat_message (request_hash);

create index idx_message_session_created
    on chat_message (session_id, created_at);

create index idx_message_session_turn
    on chat_message (session_id, turn_id, seq_no);

create index idx_message_turn_seq
    on chat_message (turn_id, seq_no);

create table news
(
    id                 bigint auto_increment comment '新闻表id'
        primary key,
    title              varchar(512)                                not null comment '新闻标题',
    summary            text                                        not null comment '新闻摘要',
    image_url          varchar(512)                                not null comment '新闻图片url',
    link               varchar(512)                                not null comment '新闻链接',
    source             char(16)                                    not null comment '新闻来源',
    publication_time   bigint  default ((unix_timestamp() * 1000)) null comment '新闻报道时间戳，毫秒级别',
    deleted            tinyint default 0                           not null comment '逻辑删除标识',
    context            text                                        null comment '压缩后的正文',
    gmt_create         datetime                                    not null comment '创建时间',
    gmt_modified       datetime                                    not null comment '最后更新时间',
    lock_version       int                                         null comment '乐观锁版本',
    title_cn           varchar(512)                                null comment '标题翻译',
    summary_cn         text                                        null comment '摘要中文翻译',
    context_cn         text                                        null comment '正文中文翻译',
    content_status     tinyint default 0                           not null comment '获取正文状态，0-待抓取，1-成功，2-失败',
    translation_status tinyint default 0                           not null comment '翻译状态：0-待翻译，1-成功，2-失败',
    vector_status      tinyint default 0                           null comment '向量化状态：0-待向量化，1-成功，2-失败',
    canonical_id       varchar(64)                                 null comment 'Story ID，用于同题去重',
    canonical_status   tinyint default 0                           null comment '归簇状态：0-待归簇，1-已归簇，2-失败',
    es_indexed         tinyint default 0                           null comment 'ES索引状态：0-未索引，1-已索引',
    category           varchar(64)                                 null comment '新闻分类',
    category_id        bigint                                      null comment '分类ID，关联 news_category.id',
    constraint id
        unique (id),
    constraint link
        unique (link)
);

create index idx_es_indexed
    on news (es_indexed);

create index idx_news_canonical_id
    on news (canonical_id);

create index idx_news_category_id
    on news (category_id);

create index idx_news_vector_status
    on news (vector_status);

create table news_category
(
    id           bigint auto_increment comment '主键ID'
        primary key,
    name         varchar(64)                        not null comment '分类名称',
    deleted      tinyint  default 0                 not null comment '逻辑删除标记',
    lock_version int      default 0                 not null comment '乐观锁版本',
    gmt_create   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_news_category_name
        unique (name)
)
    comment '新闻分类表';

create table sms_send_record
(
    id                  bigint auto_increment comment '主键ID'
        primary key,
    out_id              varchar(64)                           not null comment '业务请求ID',
    phone               varchar(20)                           not null comment '手机号',
    scene               varchar(32) default 'LOGIN'           not null comment '业务场景',
    state               varchar(32)                           not null comment '发送状态：INIT/SUCCESS/FAILED',
    provider_code       varchar(64)                           null comment '服务商返回码',
    provider_message    varchar(512)                          null comment '服务商返回信息',
    provider_request_id varchar(128)                          null comment '服务商请求ID',
    send_success_time   datetime                              null comment '发送成功时间',
    deleted             tinyint     default 0                 not null comment '逻辑删除标识',
    lock_version        int         default 0                 null comment '乐观锁版本',
    gmt_create          datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified        datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_sms_send_record_out_id
        unique (out_id)
)
    comment '短信发送记录表';

create index idx_sms_send_record_phone_create
    on sms_send_record (phone, gmt_create);

create index idx_sms_send_record_state_create
    on sms_send_record (state, gmt_create);

create table user_account
(
    id           bigint auto_increment comment '主键ID'
        primary key,
    username     varchar(64)                        null comment '用户名，可重复；默认取"用户+手机号后4位"',
    email        varchar(128)                       null comment '邮箱，可为空',
    phone        varchar(20)                        not null comment '手机号',
    deleted      tinyint  default 0                 not null comment '逻辑删除标识',
    lock_version int      default 0                 null comment '乐观锁版本',
    gmt_create   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    gmt_modified datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_user_account_phone
        unique (phone)
)
    comment '用户表';

