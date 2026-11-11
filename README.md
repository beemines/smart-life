<div align="center">

# Smart Life

**本地生活服务平台 · Java 后端 + Vue 前端**

商户发现 / 优惠券抢购 / 探店分享 / 关注动态

[![Build](https://github.com/beemines/smart-life/actions/workflows/ci.yml/badge.svg)](https://github.com/beemines/smart-life/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?style=flat-square)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-square)](LICENSE)

[功能概览](#功能概览) · [实现要点](#实现要点) · [快速开始](#快速开始) · [接口示例](#接口示例) · [开发与测试](#开发与测试)

</div>

## 项目介绍

SmartLife 面向本地生活消费场景，提供商户与分类查询、优惠券抢购、探店笔记、关注动态和用户签到等后端接口。项目重点放在缓存访问路径、Redis 数据结构和异步订单处理上。

当前仓库包含 **Spring Boot 后端、Vue 3 前端、Nginx 配置、MySQL 初始化脚本和测试代码**。前端直接通过 HTML 引入 Vue、Element Plus 与 Axios，无需 npm 打包。

## 功能概览

| 模块 | 当前功能 |
| --- | --- |
| 用户与会话 | 模拟短信验证码登录、Redis Token 存储、请求续期、当前用户信息 |
| 商户与分类 | 商户详情、分类列表、按分类分页、名称搜索、商户信息更新 |
| 优惠券 | 普通券与秒杀券创建、商户券列表、库存校验、一人一单、异步创建订单 |
| 探店笔记 | 发布笔记、热门笔记、个人笔记、点赞与取消点赞、最近点赞用户 |
| 关注动态 | 关注与取消关注、共同关注、基于收件箱的滚动动态查询 |
| 用户签到 | Redis Bitmap 签到、连续签到天数统计 |
| 图片上传 | 本地文件存储，可配置上传目录 |

## 实现要点

### 1. 商户详情的多级缓存

商户详情先读取 Caffeine 本地缓存，再读取 Redis；未命中时结合布隆过滤器判断，最后查询 MySQL。数据库未查到的结果使用短期空值缓存；普通缓存写入时增加随机过期时间。商户更新后删除当前实例的本地缓存和 Redis 缓存。

- 商户详情本地缓存：最多 10,000 项，写入后 3 分钟过期。
- 商户 ID 布隆过滤器：启动时从数据库初始化。
- 商户分类列表：独立使用 Caffeine + Redis 缓存。

入口：[ShopServiceImpl](src/main/java/com/bee/smartlife/service/impl/ShopServiceImpl.java)、[CacheClient](src/main/java/com/bee/smartlife/utils/CacheClient.java)。

### 2. Redis Lua + RocketMQ 异步抢购

```text
抢购请求
  └─ Redis Lua：检查库存 → 检查重复领取 → 预扣库存
       └─ RocketMQ：发送订单消息 → 消费者创建订单
            └─ Redisson 用户锁 → 重复订单检查 → MySQL 条件扣库存 → 保存订单
```

Lua 将库存判断、重复领取判断和预扣库存放在同一次 Redis 脚本执行中。订单持久化由 RocketMQ 消费者异步处理，数据库使用 `stock > 0` 条件扣减库存；消费抛出异常时请求消息重试。

**抢购接口返回字符串订单 ID 代表请求已持久受理，不代表订单已完成落库或支付。** 客户端通过请求标识或订单 ID 查询真实结果，现有抢购页面显示处理中、已创建、已拒绝或待核对。

订单 ID 使用 UTC 秒差与 Redis 日序列，订单表以用户／优惠券组合唯一约束兜底。已有数据库需要执行一次增量迁移；序列保留、恢复约束及迁移步骤见 [订单安全说明](docs/order-safety.md)。

入口：[VoucherOrderServiceImpl](src/main/java/com/bee/smartlife/service/impl/VoucherOrderServiceImpl.java)、[seckill.lua](src/main/resources/seckill.lua)、[mq](src/main/java/com/bee/smartlife/mq)。

### 3. 会话、互动与动态

- **Redis Hash**：保存登录用户信息，拦截器读取 Token 并续期，请求结束清理 ThreadLocal。
- **Sorted Set**：保存点赞时间和关注动态，支持最近点赞用户与滚动分页。
- **Set**：保存关注关系，用交集查询共同关注。
- **Bitmap**：按月保存签到记录，统计连续签到天数。

## 技术栈

| 技术 | 版本 / 用途 |
| --- | --- |
| Vue / Element Plus / Axios | 3.5.45 / 2.14.8 / 1.21.0，页面与接口请求 |
| Nginx | 1.30.x，静态资源托管与 API 反向代理 |
| Java | 21 |
| Spring Boot | 3.5.16，Web 与配置管理 |
| MyBatis-Plus | 3.5.18.1，Boot 3 数据访问与分页 |
| MySQL | 8.4，业务数据持久化 |
| Redis | 8.4，会话、缓存、库存、点赞、关注动态、签到 |
| Caffeine | Spring Boot BOM 管理，本地缓存 |
| Redisson | 4.8.0，分布式锁与布隆过滤器 |
| RocketMQ Client | 5.5.1，使用经典 Producer / PushConsumer API |
| Hutool / Lombok | 5.8.48 / Boot BOM 管理，工具与实体简化 |
| Maven / JUnit 5 | 构建与测试 |

依赖版本以 [pom.xml](pom.xml) 为准。

## 目录结构

```text
smart-life
├── .github/workflows/ci.yml    # 后端构建与前端检查
├── frontend/                  # HTML、CSS、JavaScript 与静态图片
├── deploy/nginx/nginx.conf    # 静态页面与 API 反向代理
├── scripts/                   # 静态资源与脚本语法检查
├── docs/                      # 配置说明与后续改进
├── src/main/java/com/bee/smartlife
│   ├── config/                # Redis、MQ、MVC 与 MyBatis 配置
│   ├── controller/            # HTTP 接口
│   ├── dto/                   # 请求与响应对象
│   ├── entity/                # 数据库实体
│   ├── mapper/                # 数据访问
│   ├── mq/                    # 订单消息生产与消费
│   ├── service/               # 业务逻辑
│   └── utils/                 # 缓存、会话与锁工具
├── src/main/resources
│   ├── db/smart-life.sql        # 仅表结构，无用户数据
│   ├── db/smart-life-demo.sql   # 可选的虚构商户演示数据
│   ├── mapper/                # SQL 映射
│   ├── application.yaml        # 环境变量配置
│   └── *.lua                  # 库存与锁脚本
└── src/test/java/             # 单元测试与手动集成实验
```

## 快速开始

### 1. 准备环境

- JDK 21、Maven 3.6.3+。
- MySQL 8.0、Redis、RocketMQ NameServer 与 Broker。
- 默认端口：应用 `8081`、MySQL `3306`、Redis `6379`、NameServer `9876`。

RocketMQ 的 NameServer 和 Broker 需要同时可用；可参考 [RocketMQ 官方部署文档](https://rocketmq.apache.org/docs/quickStart/01quickstart/)。

```bash
git clone https://github.com/beemines/smart-life.git
cd smart-life
```

也可用仓库自带的 Docker Compose 启动隔离演示环境并自动导入测试数据，见 [本地开发](docs/local-development.md)。

### 2. 初始化数据库

在 MySQL 客户端执行：

```sql
SOURCE /absolute/path/to/smart-life/src/main/resources/db/smart-life.sql;
-- 可选：导入少量虚构商户数据，便于调用查询接口。
SOURCE /absolute/path/to/smart-life/src/main/resources/db/smart-life-demo.sql;
```

首次执行创建 `smartlife` 数据库及 11 张表。结构脚本用于新建环境，重复执行会提示表已存在；不要在已有业务库上直接重建表。

### 3. 设置连接信息并启动

PowerShell：

```powershell
$env:SMARTLIFE_DB_PASSWORD = "你的本机数据库密码"
# Redis 有密码时设置；无密码时无需配置。
# $env:SMARTLIFE_REDIS_PASSWORD = "你的 Redis 密码"
mvn spring-boot:run
```

Linux / macOS：

```bash
export SMARTLIFE_DB_PASSWORD='你的本机数据库密码'
mvn spring-boot:run
```

默认只监听 `127.0.0.1:8081`。连接端口、Redis 数据库、MQ 地址、上传目录等选项见 [配置说明](docs/configuration.md)。应用不会自动加载 `.env` 文件，请在终端或 IDEA 的运行配置中设置环境变量。

也可先打包，再启动：

```bash
mvn clean verify
java -jar target/smart-life-0.0.1-SNAPSHOT.jar
```

### 4. 启动前端

前端位于 `frontend/`，无需执行 `npm install` 或 `npm run build`。启动 Nginx 后访问 **http://127.0.0.1:8080/**；接口由 `/api/` 转发到后端 `127.0.0.1:8081`。

仓库提供可复用配置，具体命令见 [前端与 Nginx 启动说明](docs/frontend.md)。

### 5. 验证查询接口

```bash
curl http://127.0.0.1:8081/shop-type/list
curl http://127.0.0.1:8081/shop/1
curl "http://127.0.0.1:8081/shop/of/type?typeId=1&current=1"
```

导入演示数据后，`/shop/1` 应返回虚构商户 **SmartLife Cafe**。如果没有导入演示数据，需要自行创建商户；分类列表为空时接口会返回业务失败结果。

## 接口示例

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| POST | `/user/code?phone=...` | 生成模拟验证码，开发日志中查看 |
| POST | `/user/login` | 手机号与验证码登录 |
| GET | `/user/me` | 当前登录用户 |
| GET | `/shop-type/list` | 商户分类 |
| GET | `/shop/{id}` | 商户详情 |
| GET | `/shop/of/type?typeId=1&current=1` | 分类分页 |
| GET | `/voucher/list/{shopId}` | 商户优惠券 |
| POST | `/voucher/seckill` | 创建秒杀券并写入 Redis 库存 |
| POST | `/voucher-order/seckill/{id}` | 抢购优惠券，异步创建订单 |
| GET | `/voucher-order/result/{orderId}` | 查询自己的订单受理结果 |
| GET | `/voucher-order/request/{requestKey}` | 按当前用户的幂等请求标识查询结果 |
| GET | `/blog/hot?current=1` | 热门探店笔记 |
| PUT | `/blog/like/{id}` | 点赞 / 取消点赞 |
| GET | `/blog/of/follow?lastId=...&offset=0` | 关注动态滚动查询 |
| POST | `/user/sign` | 签到 |

登录返回的 Token 直接放入 `authorization` 请求头，当前实现无需添加 `Bearer ` 前缀。业务响应使用统一结构：

```json
{"success": true, "data": {"id": 1}}
```

失败时返回 `success: false` 与 `errorMsg`。受保护接口在未登录时返回 HTTP 401。

## 开发与测试

前端资源、JavaScript 语法与 Vue 3 模板编译检查：`npm ci --ignore-scripts && npm run check:frontend`（需要 Node.js 22+，仅用于检查，运行页面无需 Node.js）。

```bash
# 无需连接外部服务：编译、单元测试、打包。
mvn clean verify

# 自动集成回归：先配置隔离服务及 SMARTLIFE_INTEGRATION_TESTS=true。
mvn -Dtest=StackUpgradeIT,OrderSafetyIT,RedisIdWorkerIT,OrderConsumptionIT test

# 手动集成实验：需要独立的 MySQL、Redis 与 RocketMQ 测试环境。
mvn -Dtest=SmartLifeApplicationIT,RedissonLockIT test
```

CI 在 JDK 21 上执行 `mvn clean verify`，另检查前端资源、Vue 模板、Nginx 配置和静态页面访问。独立集成任务通过 Docker 启动 MySQL 8.4、Redis 8.4、RocketMQ 5.5.0，运行 `StackUpgradeIT`、`OrderSafetyIT`、`RedisIdWorkerIT` 、`OrderConsumptionIT` 和 `OrderRequestRecoveryIT`，验证原有业务链路、数据库唯一约束／回滚／迁移、真实 Redis 发号，以及订单重投、锁竞争、事务提交边界与幂等补偿。运行命令见 [本地开发与集成验证](docs/local-development.md)。其余历史 `*IT` 仍是手动实验，其中包含批量写 Redis 的操作。

旧开发库先执行 [失败记录迁移](src/main/resources/db/migrations/2026-11-09-order-consumption-failure.sql)，再执行 [受理与恢复迁移](src/main/resources/db/migrations/2026-11-11-order-request-recovery.sql)。消费安全改动需要停止旧版生产者／消费者后协调切换，不支持新旧消费者混跑；说明及核对步骤见 [订单消费与补偿](docs/order-consumption.md) 和 [受理恢复操作说明](docs/order-recovery.md)。

## 当前边界与后续方向

当前版本适合本地学习、功能演示与后端工程实践。短信发送为模拟实现；支付、核销、退款、订单超时关闭和登出尚未完成。商户与上传相关接口也尚未建立完整的商家权限体系。

已实现订单 ID 重投识别、锁竞争重试、持锁至事务完成、确定业务拒绝记录及订单归属校验的 Redis 补偿，并增加持久受理、原 ID 重发、结果查询与有审计的超时恢复。历史预占归属无法验证或库存损坏时保留人工核对记录，不根据库存差额盲目补量。支付闭环与可复现压测仍待完成，详见 [Roadmap](docs/roadmap.md)。

逐项改造前后、验收场景及完成状态见 [todolist.md](todolist.md)。

## 参与贡献

欢迎提交 Issue 讨论问题与改进建议。较大改动请先说明具体场景；实现时优先复用现有页面、接口与服务逻辑，保持改动范围清晰，并提供相应验证结果。

## 许可证

[MIT License](LICENSE)。随前端分发的第三方库见 [第三方声明](frontend/THIRD_PARTY_NOTICES.md)。
