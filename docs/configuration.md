# SmartLife 配置

所有项目配置在 `src/main/resources/application.yaml`。运行前在终端或 IDEA Run Configuration 中设置环境变量；不要把真实密码提交到仓库。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SMARTLIFE_SERVER_ADDRESS` | `127.0.0.1` | HTTP 监听地址 |
| `SMARTLIFE_SERVER_PORT` | `8081` | HTTP 端口 |
| `SMARTLIFE_DB_HOST` | `127.0.0.1` | MySQL 主机 |
| `SMARTLIFE_DB_PORT` | `3306` | MySQL 端口 |
| `SMARTLIFE_DB_NAME` | `smartlife` | 数据库名称 |
| `SMARTLIFE_DB_USER` | `root` | MySQL 用户，建议独立创建应用账户 |
| `SMARTLIFE_DB_PASSWORD` | 空 | MySQL 密码，按本机配置设置 |
| `SMARTLIFE_REDIS_HOST` | `127.0.0.1` | Redis 主机 |
| `SMARTLIFE_REDIS_PORT` | `6379` | Redis 端口 |
| `SMARTLIFE_REDIS_PASSWORD` | 空 | Redis 密码 |
| `SMARTLIFE_REDIS_DATABASE` | `0` | Redis 数据库，建议使用独立实例 / 测试库 |
| `SMARTLIFE_REDIS_SSL` | `false` | 是否通过 TLS 连接 Redis |
| `SMARTLIFE_MQ_NAME_SERVER` | `127.0.0.1:9876` | RocketMQ NameServer |
| `SMARTLIFE_MQ_TOPIC` | `smartlife-seckill-order` | 秒杀订单 Topic |
| `SMARTLIFE_UPLOAD_DIR` | `./uploads` | 图片根目录，相对于启动目录 |
| `SMARTLIFE_LOG_LEVEL` | `debug` | 项目日志级别，模拟验证码在 DEBUG 日志中 |

Spring Redis 与 Redisson 复用同一组主机、端口、密码、数据库和 TLS 配置，避免缓存与锁连接到不同实例。本项目使用单节点 Redis 配置；未配置 Sentinel 或 Cluster。

上传接口返回 `/blogs/...` 相对名称。仓库的 Nginx 配置将前端 `/imgs/...` 请求映射到静态图片或 `uploads/` 中的上传文件；具体配置见 [前端启动说明](frontend.md)。

## IDEA 运行

1. 打开项目根目录的 `pom.xml`，等待 Maven 导入。
2. Project SDK 与 Maven Runner JRE 选择 JDK 8 或 11。
3. 运行 `com.bee.smartlife.SmartLifeApplication`。
4. 在运行配置的 Environment variables 中设置数据库密码及其他连接参数。

## 环境隔离

结构脚本创建一个新的 `smartlife` 库，不会迁移旧数据库中的业务数据。原有数据如需保留，应单独备份并在确认后迁移。

Redis Key 目前按业务前缀组织，建议给项目使用独立 Redis 实例或数据库，避免与其他应用的 `cache:*`、`login:*` 等 Key 冲突。
