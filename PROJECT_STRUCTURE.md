# NFTurbo 项目结构文档

## 项目概览

NFTurbo 是一个基于 Spring Cloud Alibaba 的微服务架构数字藏品交易平台，采用多层 Maven 模块结构。

```
NFTurbo (根项目)
├── nft-turbo-gateway          # API 网关
├── nft-turbo-auth             # 认证服务
├── nft-turbo-admin            # 管理后台
├── nft-turbo-check            # 健康检查服务
├── nft-turbo-common           # 公共组件库（父模块）
├── nft-turbo-business         # 业务服务（父模块）
└── nft-turbo-config           # 配置文件和文档
```

---

## 一、基础设施层（nft-turbo-common）

### 1.1 核心基础模块

#### **nft-turbo-base**
**职责：** 项目基础组件和工具类
- 异常体系（BizException、SystemException、ErrorCode）
- 响应封装（BaseResponse、SingleResponse、PageResponse）
- 请求基类（BaseRequest、PageRequest）
- 工具类（线程池、Bean 验证、HTTP 工具）
- 状态机基类
- 手机号验证器

**依赖：** 无内部依赖
**被依赖：** 几乎所有业务模块

**配置文件：**
- `base.yml` - 集中管理所有中间件连接地址

---

#### **nft-turbo-api**
**职责：** API 接口定义和契约
- 订单 API（OrderErrorCode）
- 支付 API（PayErrorCode）
- 用户权限和角色定义

**依赖：** nft-turbo-base
**被依赖：** 所有业务模块

---

#### **nft-turbo-web**
**职责：** Web 层通用组件
- 全局异常处理器（GlobalWebExceptionHandler）
- 统一响应封装（Result、MultiResult）
- Token 过滤器
- 响应转换工具

**依赖：** nft-turbo-base, nft-turbo-sa-token, nft-turbo-cache
**被依赖：** 需要 Web 功能的业务模块

---

### 1.2 数据访问层

#### **nft-turbo-datasource**
**职责：** 数据库连接和 ORM 配置
- Druid 连接池配置
- MyBatis-Plus 配置和插件
- ShardingSphere 分库分表支持
- 自动填充处理器（创建时间、修改时间）
- BaseEntity 基类

**技术栈：**
- MyBatis 3.0.3 + MyBatis-Plus 3.5.5
- Druid 1.2.20
- MySQL Connector 8.0.27
- ShardingSphere 5.2.1

**依赖：** 无（不依赖 base）
**被依赖：** user, order, pay, chain, notice, collection, box, interface

**配置文件：**
- `datasource.yml` - 单数据源配置
- `datasource-sharding.yml` - 分库分表配置

---

### 1.3 中间件集成层

#### **nft-turbo-cache**
**职责：** Redis 缓存抽象
**依赖：** nft-turbo-base
**配置文件：** `cache.yml`

---

#### **nft-turbo-mq**
**职责：** RocketMQ 消息队列集成
**依赖：** nft-turbo-base
**配置文件：** `stream.yml`

---

#### **nft-turbo-rpc**
**职责：** Dubbo RPC 配置
**依赖：** nft-turbo-base
**配置文件：** `rpc.yml`

---

#### **nft-turbo-es**
**职责：** Elasticsearch 集成
**依赖：** nft-turbo-base
**配置文件：** `es.yml`

---

### 1.4 分布式解决方案

#### **nft-turbo-lock**
**职责：** 基于 Redis 的分布式锁
**依赖：** nft-turbo-base

---

#### **nft-turbo-limiter**
**职责：** 限流组件
**依赖：** nft-turbo-base
**配置文件：** `limiter.yml`

---

#### **nft-turbo-seata**
**职责：** Seata 分布式事务
**依赖：** nft-turbo-base
**配置文件：** `seata.yml`

---

#### **nft-turbo-tcc**
**职责：** TCC 事务模式实现
- TransactionLog 实体
- TCC 请求和响应封装
- 事务日志服务

**依赖：** nft-turbo-datasource

---

### 1.5 其他公共组件

#### **nft-turbo-job**
**职责：** XXL-Job 定时任务集成
**配置文件：** `job.yml`

---

#### **nft-turbo-file**
**职责：** OSS 文件存储服务

---

#### **nft-turbo-sms**
**职责：** 短信服务（支持 Mock）

---

#### **nft-turbo-sa-token**
**职责：** Sa-Token 认证框架依赖

---

#### **nft-turbo-config**
**职责：** 配置管理
**配置文件：** `config.yml`

---

#### **nft-turbo-order-client**
**职责：** 订单服务客户端

---

### 1.6 可观测性

#### **nft-turbo-prometheus**
**职责：** Prometheus 监控指标
**配置文件：** `prometheus.yml`

---

#### **nft-turbo-skywalking**
**职责：** SkyWalking 链路追踪


---

## 二、网关和认证层

### 2.1 nft-turbo-gateway
**职责：** API 网关
- 路由转发
- 跨域配置
- Sentinel 限流
- Sa-Token 权限校验

**技术栈：** Spring Cloud Gateway
**端口：** 8081

**依赖：**
- nft-turbo-base
- nft-turbo-cache
- nft-turbo-config

**路由配置：**
```yaml
/auth/**     → nfturbo-auth
/trade/**    → nfturbo-business
/order/**    → nfturbo-business
/user/**     → nfturbo-business
/collection/** → nfturbo-business
/wxPay/**    → nfturbo-business
/box/**      → nfturbo-business
```

---

### 2.2 nft-turbo-auth
**职责：** 认证服务
- 用户登录
- Token 管理
- 权限验证

**技术栈：** Sa-Token
**端口：** 8082

**依赖：**
- nft-turbo-base
- nft-turbo-rpc
- nft-turbo-cache

---

### 2.3 nft-turbo-admin
**职责：** 管理后台服务
- 管理员认证
- 后台管理功能

**依赖：**
- nft-turbo-base
- nft-turbo-cache
- nft-turbo-rpc

---

## 三、业务服务层（nft-turbo-business）

### 3.1 用户模块

#### **nft-turbo-user**
**职责：** 用户管理
- 用户注册、登录
- 用户信息管理
- 实名认证
- 用户操作流水

**端口：** 8083

**依赖：**
- nft-turbo-base
- nft-turbo-datasource
- nft-turbo-web
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-limiter
- nft-turbo-file
- nft-turbo-lock

**数据库表：**
- `users` - 用户表
- `user_operate_stream` - 用户操作流水

---

### 3.2 商品模块（nft-turbo-goods）

#### **nft-turbo-collection**
**职责：** 数字藏品管理
- 藏品创建、发布
- 藏品查询
- 藏品库存管理
- 藏品空投
- 持有藏品管理

**依赖：**
- nft-turbo-base
- nft-turbo-datasource
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-mq
- nft-turbo-es
- nft-turbo-seata
- nft-turbo-limiter

**数据库表：**
- `collection` - 藏品表
- `collection_stream` - 藏品流水
- `collection_inventory_stream` - 库存流水
- `collection_airdrop_stream` - 空投流水
- `held_collection` - 持有藏品表

---

#### **nft-turbo-box**
**职责：** 盲盒功能
- 盲盒创建
- 盲盒开启
- 盲盒库存管理

**依赖：**
- nft-turbo-base
- nft-turbo-datasource
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-mq
- nft-turbo-es
- nft-turbo-seata
- nft-turbo-limiter

**数据库表：**
- `blind_box` - 盲盒表
- `blind_box_item` - 盲盒物品表
- `blind_box_inventory_stream` - 盲盒库存流水

---

#### **nft-turbo-interface**
**职责：** 商品接口服务
- 商品预约
- 商品查询接口

**依赖：**
- nft-turbo-base
- nft-turbo-datasource
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-mq
- nft-turbo-es
- nft-turbo-seata

**数据库表：**
- `goods_book` - 商品预约表

---

### 3.3 交易模块

#### **nft-turbo-order**
**职责：** 订单管理
- 订单创建
- 订单查询
- 订单状态流转
- 订单流水

**依赖：**
- nft-turbo-base
- nft-turbo-datasource (分库分表)
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-job
- nft-turbo-mq
- nft-turbo-seata
- nft-turbo-limiter

**数据库表：**
- `trade_order` - 订单表（分表）
- `trade_order_stream` - 订单流水

---

#### **nft-turbo-pay**
**职责：** 支付服务
- 微信支付集成
- 支付单管理
- 退款管理
- 支付对账

**依赖：**
- nft-turbo-base
- nft-turbo-datasource
- nft-turbo-web
- nft-turbo-rpc
- nft-turbo-job
- nft-turbo-seata
- nft-turbo-lock

**数据库表：**
- `pay_order` - 支付单表
- `refund_order` - 退款单表

**证书文件：**
- `apiclient_cert.p12`
- `apiclient_cert.pem`
- `apiclient_key.pem`
- `platform_cert.pem`

---

#### **nft-turbo-trade**
**职责：** 交易编排服务
- 交易流程编排
- 下单流程
- 支付流程

**依赖：**
- nft-turbo-base
- nft-turbo-web
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-mq
- nft-turbo-limiter
- nft-turbo-order-client

---

### 3.4 库存模块

#### **nft-turbo-inventory**
**职责：** 库存管理
- 库存扣减
- 库存回滚
- TCC 事务支持

**依赖：**
- nft-turbo-base
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-limiter
- nft-turbo-tcc

---

### 3.5 区块链模块

#### **nft-turbo-chain**
**职责：** 区块链集成
- 藏品上链
- 链操作记录
- 文昌链集成

**依赖：**
- nft-turbo-base
- nft-turbo-datasource
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-mq
- nft-turbo-limiter

**数据库表：**
- `chain_operate_info` - 链操作信息表

---

### 3.6 通知模块

#### **nft-turbo-notice**
**职责：** 消息通知
- 站内通知
- 短信通知
- 通知模板管理

**依赖：**
- nft-turbo-base
- nft-turbo-datasource
- nft-turbo-rpc
- nft-turbo-cache

---

### 3.7 应用服务

#### **nft-turbo-app**
**职责：** 主应用服务（聚合服务）
- 整合多个业务模块
- 提供统一的业务接口

**依赖：**
- nft-turbo-base
- nft-turbo-datasource (分库分表)
- nft-turbo-rpc
- nft-turbo-cache
- nft-turbo-job
- nft-turbo-mq
- nft-turbo-es
- nft-turbo-seata
- nft-turbo-prometheus
- nft-turbo-limiter

---

### 3.8 健康检查

#### **nft-turbo-check**
**职责：** 健康检查服务
- 服务健康检查
- 依赖检查

**依赖：**
- nft-turbo-base
- nft-turbo-rpc
- nft-turbo-job


---

## 四、模块依赖关系图

### 4.1 分层依赖关系

```
┌─────────────────────────────────────────────────────────┐
│                    业务服务层                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐│
│  │  user    │  │  order   │  │   pay    │  │collection││
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘│
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐│
│  │  trade   │  │inventory │  │  chain   │  │  notice  ││
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘│
└─────────────────────────────────────────────────────────┘
                        ↓ 依赖
┌─────────────────────────────────────────────────────────┐
│                  公共组件层                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐│
│  │   web    │  │datasource│  │   rpc    │  │  cache   ││
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘│
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐│
│  │   mq     │  │  lock    │  │ limiter  │  │  seata   ││
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘│
└─────────────────────────────────────────────────────────┘
                        ↓ 依赖
┌─────────────────────────────────────────────────────────┐
│                    基础层                                 │
│         ┌──────────┐          ┌──────────┐              │
│         │   base   │          │   api    │              │
│         └──────────┘          └──────────┘              │
└─────────────────────────────────────────────────────────┘
```

### 4.2 典型业务模块依赖示例

**nft-turbo-user 依赖：**
```
nft-turbo-user
├── nft-turbo-base          (基础组件)
├── nft-turbo-api           (API 定义)
├── nft-turbo-web           (Web 组件)
├── nft-turbo-datasource    (数据库)
├── nft-turbo-rpc           (RPC)
├── nft-turbo-cache         (缓存)
├── nft-turbo-limiter       (限流)
├── nft-turbo-file          (文件)
├── nft-turbo-lock          (分布式锁)
└── nft-turbo-skywalking    (链路追踪)
```

**nft-turbo-order 依赖：**
```
nft-turbo-order
├── nft-turbo-base
├── nft-turbo-datasource (分库分表)
├── nft-turbo-rpc
├── nft-turbo-cache
├── nft-turbo-job           (定时任务)
├── nft-turbo-mq            (消息队列)
├── nft-turbo-seata         (分布式事务)
└── nft-turbo-limiter
```

---

## 五、配置文件加载机制

### 5.1 配置文件层次

```
业务模块 application.yml
    ↓ spring.config.import
┌─────────────────────────────────────┐
│ base.yml         (基础配置)          │
│ datasource.yml   (数据源配置)        │
│ cache.yml        (缓存配置)          │
│ rpc.yml          (RPC配置)           │
│ mq.yml           (消息队列配置)      │
│ ...                                 │
└─────────────────────────────────────┘
```

### 5.2 配置导入示例

```yaml
# nft-turbo-user/application.yml
spring:
  config:
    import: classpath:base.yml,
            classpath:datasource.yml,
            classpath:cache.yml,
            classpath:rpc.yml,
            classpath:limiter.yml
```

### 5.3 配置变量解析流程

```
1. 业务模块启动
   ↓
2. 加载 application.yml
   ↓
3. 解析 spring.config.import
   ↓
4. 按顺序加载配置文件：
   - base.yml (定义 nft.turbo.mysql.*)
   - datasource.yml (使用 ${nft.turbo.mysql.url})
   ↓
5. Spring Boot 合并所有配置
   ↓
6. 解析占位符 ${...}
   ↓
7. 创建 Bean（DataSource, RedisTemplate 等）
```

---

## 六、端口分配

| 服务 | 端口 | 说明 |
|------|------|------|
| nft-turbo-gateway | 8081 | API 网关 |
| nft-turbo-auth | 8082 | 认证服务 |
| nft-turbo-user | 8083 | 用户服务 |

---

## 七、数据库表分布

### 7.1 用户模块
- `users` - 用户表
- `user_operate_stream` - 用户操作流水

### 7.2 订单模块（分表）
- `trade_order_0` ~ `trade_order_n` - 订单表（分表）
- `trade_order_stream` - 订单流水

### 7.3 支付模块
- `pay_order` - 支付单表
- `refund_order` - 退款单表

### 7.4 藏品模块
- `collection` - 藏品表
- `collection_stream` - 藏品流水
- `collection_inventory_stream` - 库存流水
- `collection_airdrop_stream` - 空投流水
- `held_collection` - 持有藏品表

### 7.5 盲盒模块
- `blind_box` - 盲盒表
- `blind_box_item` - 盲盒物品表
- `blind_box_inventory_stream` - 盲盒库存流水

### 7.6 商品模块
- `goods_book` - 商品预约表

### 7.7 区块链模块
- `chain_operate_info` - 链操作信息表

---

## 八、技术栈总结

### 8.1 核心框架
- **Spring Boot**: 3.2.2
- **Spring Cloud**: 2023.0.0
- **Spring Cloud Alibaba**: 2023.0.1.2
- **Apache Dubbo**: 3.2.10

### 8.2 数据存储
- **MySQL**: 8.x
- **连接池**: Druid 1.2.20
- **ORM**: MyBatis 3.0.3 + MyBatis-Plus 3.5.5
- **分库分表**: ShardingSphere 5.2.1
- **缓存**: Redis
- **搜索**: Elasticsearch (可选)

### 8.3 中间件
- **服务发现**: Nacos
- **配置中心**: Nacos
- **消息队列**: RocketMQ 5.3.0
- **限流降级**: Sentinel
- **分布式事务**: Seata
- **定时任务**: XXL-Job
- **API 网关**: Spring Cloud Gateway

### 8.4 工具库
- **Lombok**: 1.18.30
- **MapStruct**: 1.6.0
- **Fastjson2**: 2.0.42
- **Guava**: 32.1.3-jre
- **Hutool**: 5.8.22

### 8.5 可观测性
- **链路追踪**: SkyWalking (可选)
- **监控指标**: Prometheus (可选)
- **日志**: Logback

---

## 九、Maven 继承结构

### 9.1 继承层次

```
spring-boot-starter-parent (3.2.2)
    ↓
NFTurbo (根 POM)
    ├── dependencyManagement (统一版本管理)
    ├── modules (聚合所有顶层模块)
    ↓
nft-turbo-common (中间层父 POM)
    ├── dependencies (公共依赖)
    ├── modules (聚合 common 子模块)
    ↓
nft-turbo-base, nft-turbo-web, ... (叶子模块)

nft-turbo-business (中间层父 POM)
    ├── modules (聚合 business 子模块)
    ↓
nft-turbo-goods (第三层父 POM)
    ├── modules (聚合 goods 子模块)
    ↓
nft-turbo-collection, nft-turbo-box, ... (叶子模块)
```

### 9.2 依赖管理策略

- **根 POM**: 使用 `<dependencyManagement>` 统一版本，不实际引入
- **中间层 POM**: 使用 `<dependencies>` 为子模块提供公共依赖
- **叶子模块**: 只声明特有依赖，公共依赖自动继承

---

## 十、关键设计模式

### 10.1 配置外部化
- 所有中间件地址集中在 `base.yml`
- 各模块通过占位符引用配置
- 支持多环境切换

### 10.2 依赖倒置
- datasource 模块定义占位符（接口）
- base 模块提供配置值（实现）
- 业务模块组装两者

### 10.3 模块解耦
- 公共组件不依赖业务模块
- 业务模块通过 API 模块定义契约
- 通过 Dubbo RPC 实现服务间通信

### 10.4 分层架构
- 基础层：base, api
- 公共组件层：datasource, cache, rpc, mq
- 业务服务层：user, order, pay, collection
- 网关层：gateway, auth

---

## 十一、开发指南

### 11.1 新增业务模块步骤

1. 在 `nft-turbo-business/pom.xml` 中添加 `<module>`
2. 创建模块目录和 pom.xml
3. 在 pom.xml 中添加必要依赖（base, datasource, rpc 等）
4. 创建 `application.yml` 并导入配置文件
5. 实现业务逻辑

### 11.2 配置文件使用规范

```yaml
# application.yml 标准模板
spring:
  application:
    name: @application.name@
  config:
    import: classpath:base.yml,
            classpath:datasource.yml,  # 需要数据库
            classpath:cache.yml,       # 需要缓存
            classpath:rpc.yml,         # 需要 RPC
            classpath:limiter.yml      # 需要限流

server:
  port: 808X  # 分配端口
```

### 11.3 依赖选择指南

| 功能需求 | 需要的依赖 |
|---------|-----------|
| 基础功能 | nft-turbo-base |
| Web 接口 | nft-turbo-web |
| 数据库访问 | nft-turbo-datasource |
| RPC 调用 | nft-turbo-rpc |
| 缓存 | nft-turbo-cache |
| 消息队列 | nft-turbo-mq |
| 限流 | nft-turbo-limiter |
| 分布式锁 | nft-turbo-lock |
| 分布式事务 | nft-turbo-seata 或 nft-turbo-tcc |
| 定时任务 | nft-turbo-job |
| 文件上传 | nft-turbo-file |
| 短信发送 | nft-turbo-sms |

---

## 十二、常见问题

### Q1: datasource 模块需要依赖 base 模块吗？
**A:** 不需要。datasource 只提供配置模板，使用占位符。业务模块同时依赖 base 和 datasource，由 Spring Boot 在运行时解析占位符。

### Q2: 如何切换单数据源和分库分表？
**A:** 在 `application.yml` 中：
- 单数据源：`import: classpath:datasource.yml`
- 分库分表：`import: classpath:datasource-sharding.yml`

### Q3: 配置文件的加载顺序是什么？
**A:** 按 `spring.config.import` 中的顺序加载，后加载的配置会覆盖先加载的同名配置。

### Q4: 如何修改数据库连接地址？
**A:** 只需修改 `nft-turbo-base/src/main/resources/base.yml` 中的 `nft.turbo.mysql.*` 配置。

---

**文档版本**: 1.0  
**最后更新**: 2024  
**维护者**: NFTurbo Team
