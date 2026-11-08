# 本地开发与集成验证

需要 JDK 21、Maven 3.6.3+、Docker Compose，以及 Nginx 1.30.x（仅前端）。从仓库根目录运行。

## 启动隔离服务

```bash
docker compose -p smart-life-dev -f compose.dev.yaml up -d --wait
```

Compose 自动在专用 MySQL 卷初始化表结构和两家虚构商户。MySQL、Redis、NameServer、Broker 仅绑定本机端口 `13306`、`16379`、`19876`、`11011`。首次启动 Broker 需完成注册，可检查：

```bash
docker compose -p smart-life-dev -f compose.dev.yaml exec -T broker sh mqadmin clusterList -n nameserver:9876
```

出现 `smart-life-dev` 即可。MySQL 默认密码 `local-test-only` 仅用于该本地测试环境；可以在首次建库前设置 `SMARTLIFE_DEV_MYSQL_PASSWORD` 替换，并同步应用密码。

## 启动后端

PowerShell：

```powershell
$env:JAVA_HOME = '你的 JDK 21 目录'
$env:SMARTLIFE_DB_PORT = '13306'
$env:SMARTLIFE_DB_PASSWORD = 'local-test-only'
$env:SMARTLIFE_REDIS_PORT = '16379'
$env:SMARTLIFE_MQ_NAME_SERVER = '127.0.0.1:19876'
mvn clean verify
& "$env:JAVA_HOME\bin\java.exe" -jar target/smart-life-0.0.1-SNAPSHOT.jar
```

Linux / macOS：

```bash
export SMARTLIFE_DB_PORT=13306 SMARTLIFE_DB_PASSWORD=local-test-only
export SMARTLIFE_REDIS_PORT=16379 SMARTLIFE_MQ_NAME_SERVER=127.0.0.1:19876
mvn clean verify
java -jar target/smart-life-0.0.1-SNAPSHOT.jar
```

随后按 [Nginx 启动说明](frontend.md) 打开前端。登录验证码是模拟发送，在后端 DEBUG 日志中查看，不会发送真实短信。

## 自动验证

单元测试不需要外部服务：`mvn clean verify`。

设置上述连接参数，并启用专用集成测试：

```powershell
$env:SMARTLIFE_INTEGRATION_TESTS = 'true'
mvn '-Dtest=StackUpgradeIT,OrderSafetyIT,RedisIdWorkerIT' test
```

Linux / macOS：`SMARTLIFE_INTEGRATION_TESTS=true mvn -Dtest=StackUpgradeIT,OrderSafetyIT,RedisIdWorkerIT test`。

`StackUpgradeIT` 启动随机端口的应用，验证未登录 401、查询与分页、上传删除、验证码登录、Lua 预扣库存、真实 MQ 消费与数据库落单、重复领取拦截。`OrderSafetyIT` 验证数据库并发唯一约束、事务回滚与安全迁移；`RedisIdWorkerIT` 验证真实 Redis 共享序列、跨日和独立 JVM 重启。测试只在该隔离环境运行，使用合成账号和优惠券，并清理自己的数据。不要针对现有业务库运行。历史的批量 Redis 实验不在该测试范围。

已存在的开发数据库／Compose 卷不会重复执行初始化 DDL，使用本次唯一约束前需按 [订单安全说明](order-safety.md) 执行一次旧库迁移。

前端开发检查：

```bash
npm ci --ignore-scripts
npm run check:frontend
```

停止服务可用 `docker compose -p smart-life-dev -f compose.dev.yaml down`；默认保留 MySQL 演示卷。Windows 重新打包前先停止正在运行的 JAR，避免文件被占用。

当前客户端为 RocketMQ 5.5.1，官方 Broker 镜像为 5.5.0；经典 Producer / PushConsumer 链路由真实集成测试验证。升级过程与结果见 [验证记录](validation/stack-upgrade.md)。
