# 抢购受理、查询与恢复

## 状态与边界

沿用原订单模块和一人一券终身一次规则。先向 MySQL `tb_voucher_order_request` 提交 PREPARING，再执行原子 Redis 预占；发送前提交 PENDING。消费创建订单和 CREATED 同事务提交；确定业务拒绝与失败结果同事务提交，再幂等补偿。无法证明预占归属时进入 REVIEW，保留人工核对。

`Idempotency-Key` 支持16–64位字母、数字、下划线或短横线。相同用户、相同 key 固定对应原订单；同 key 换券会拒绝。省略时由服务生成。前端先保存随机 key，超时、刷新、继续操作沿用它。返回和查询中的订单 ID 均为字符串，避免 JavaScript 精度损失。

发送异常或非 SEND_OK 不能证明 Broker 未收到，不直接退库存：保留原请求和预占，记录 last_error，恢复仍发送原订单 ID。Broker 连续不可用时恢复审计为 RETRY，不宣称恢复成功。已提交的成功／拒绝终态不能被晚到的发送结果覆盖。

GET `/voucher-order/result/{orderId}` 与 `/voucher-order/request/{requestKey}` 均要求登录、校验归属。未知和他人订单返回相同错误，不对外开放恢复写接口。该查询表达受理结果，不是支付状态。

现有商户详情页最多自动查询12次，随后显示继续查询；网络异常显示待确认。继续不会创建新请求；只有明确拒绝后的新一次“限时抢购”才创建新 key。浏览器仅在登录期间保存随机命名空间，重新登录清空上次订单缓存，不把 bearer token 拼进缓存键。

## 部署

先执行既有唯一约束、失败表迁移，再执行 `src/main/resources/db/migrations/2026-11-11-order-request-recovery.sql`，新建库已包含全部表。两个新表只增不删，不自动回填历史请求；旧消息仍按原订单／失败表处理。

暂停领券、停止旧生产者和消费者、核对在途请求后协调切换。禁止新旧消费者混跑或直接回滚至不识别终态的版本；保留原失败记录和新增受理记录。MySQL 连接初始化为 UTC，恢复时用数据库时钟判断年龄，避免应用时区造成误恢复。

## 预览与执行

设置既有数据库、Redis、MQ环境变量，并先 `mvn clean verify`。PowerShell 默认只预览：

```powershell
./scripts/recover-orders.ps1 -Batch 20 -AgeSeconds 120
./scripts/recover-orders.ps1 -OrderId 658153834886987779 -AgeSeconds 120
./scripts/recover-orders.ps1 -OrderId 658153834886987779 -AgeSeconds 120 -Execute
```

未指定 OrderId 才扫描批次；显式0或负数报错。每次执行只处理本次打印预览的订单 ID，执行前再次检查终态和年龄，在订单锁／用户锁内重查，不扩大范围。至少保留30秒在途窗口；默认120秒，批次1–500条。

Linux 也可直接运行 JAR（默认预览，不加最后一项）：

```bash
java -jar target/smart-life-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --smartlife.orders.consumer-enabled=false \
  --smartlife.orders.recovery-enabled=false \
  --smartlife.orders.recovery-cli=true \
  --recover.order-id=658153834886987779 --recover.execute=true
```

应用默认每60秒扫描100条，`SMARTLIFE_ORDER_RECOVERY_ENABLED=false` 可关闭。近期恢复审计提供300秒冷却，避免异常／人工记录一直占用队头；显式指定订单可绕过冷却。临时锁竞争或发送不确定记 RETRY；CLI出现 RETRY/FAILED 非零退出。MANUAL 只记录待核对，不擅自修数据。进程在审计后退出可能留下 RUNNING，后续可继续核对；不能把 RUNNING 当成功。

```sql
SELECT order_id,state,attempts,last_error,update_time
FROM tb_voucher_order_request ORDER BY update_time;
SELECT order_id,action,before_state,after_state,outcome,detail,create_time
FROM tb_voucher_order_recovery ORDER BY id;
```

预览附带券级 MySQL库存、Redis可用量、归属记录、在途／成功／已补偿数，供核对，不仅根据两处库存不相等直接增库存。PENDING 已丢失归属、错误类型／非法库存、成功失败矛盾均转人工或保持异常证据；不提供任意 Redis 数据丢失后的自动重建保证。记录长期保留，尚未实现历史归档和运营管理页。

## 演示与验证

1. 关闭消费者启动应用，使用模拟验证码登录并抢购测试券，原页面显示“已受理，处理中”。
2. 保持原 MQ topic 与 consumer group，启用消费者，点击继续查询或刷新，最终显示“订单创建成功”。
3. 在隔离测试券中故意构造 Redis有库存但MySQL无库存，查看确定拒绝及对应失败／补偿记录。不能在真实业务库制造这种差异。
4. 使用上述恢复 CLI 先预览再执行，SQL查看审计。同一恢复重跑不多建订单／不多退库存。

自动测试与实际截图见 [验证记录](validation/order-recovery.md)。
