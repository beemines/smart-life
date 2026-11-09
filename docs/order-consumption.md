# 订单消息消费、拒绝与补偿

## 保留的四个问题与改动

### ① 获取用户锁失败直接返回，消费者误认为已经处理成功

改前：张三同时领券 A、B，A 持有用户锁时，B 获取失败就正常返回；消费者随即 ACK，B 没有数据库订单。锁竞争不等于重复下单。

改后：外层服务获取订单 ID 锁，再获取用户锁，获取失败抛出明确的 OrderRetryException。消费者返回 RECONSUME_LATER；释放锁后由 RocketMQ 重投。最大重试次数为 16，耗尽后按该消费组的 DLQ 运维流程处理。消息日志包含订单 ID、消息 ID、投递次数和结果。

### ② 只查用户＋券，没有区分同订单重投与其他订单冲突

改前：数据库有订单100，再收到100或者同用户同券的200，都直接返回。正常重复点击已经由入口 Lua 拦截；不同订单 ID 的冲突属于发送结果不确定等异常情况，不能把它说成普通请求必然产生的重复订单。

改后：先检查订单 ID 的成功／失败终态，核对用户和券；再检查用户＋券。相同成功订单返回 ALREADY_PROCESSED；不同订单重复领取记录 REJECTED_ALREADY_PURCHASED。ID 与内容不符、或同 ID 同时存在成功／失败记录时不 ACK，保留日志进入重试／DLQ核对。订单 ID 锁还防止不同用户拿同一 ID 并发写入不同终态表。

### ③ 数据库库存不足直接返回，没有处理 Redis 预占与订单失败

改前：Redis 库存1、MySQL库存0时，Redis接受请求，消费却不能落单；普通 return 掩盖失败，领取资格没有可靠处理。

改后：先事务提交 tb_voucher_order_failure，再执行 Lua 补偿，最后写补偿状态。失败订单重投始终读取原失败结果，之后补库存也不会把旧失败订单变成成功。

保留原 seckill:order:<voucher> Set，另加 seckill:reservation:<voucher> Hash（userId → orderId）。补偿仅释放对应订单的预占，同一回滚重跑不多加库存；冲突请求补偿时保留原成功订单的资格。Redis成功、SQL状态回写前中断时仍是 pending，重投会安全地补记 done。

- pending：尚未可靠完成补偿，不能作为消费成功；临时故障由 MQ 重试。
- done：补偿已完成，或当前没有归属于该订单的预占。
- manual：历史资格没有订单归属，或库存丢失／格式不合法等，无法安全自动修改；记录已持久化并输出明确告警，可终结本条消息的自动处理，**不等于库存已经修好**。

新 Lua 使用显式 KEYS，适用于当前单机 Redis。未提供跨槽 Redis Cluster 迁移。Redis脚本不会回滚运行时错误前的写入，因此先校验类型和整数格式再修改。

### ④ 用户锁在事务提交前释放，锁没有覆盖完整事务

改前：@Transactional 方法内 finally 先解锁，Spring代理随后才提交。第二个消费者可能在第一笔未提交时进入。原有主键／用户券唯一约束和事务能兜底，不代表一定多扣库存，但锁范围不完整。

改后：现有 VoucherOrderServiceImpl 做非事务编排，独立 VoucherOrderPersistence 通过Spring代理运行事务；事务返回后再补偿、解锁。选择把外层锁留在公共服务入口，保证MQ和直接调用复用同一流程，不局限于某个消费者。编排挂起调用方的外层事务，终态事务独立提交，避免调用方的快照或回滚影响补偿完成记录。

## 发送超时与延迟消息

发送抛异常不代表Broker一定没收到。现在同样在订单／用户锁保护下检查终态：已成功则返回原订单ID，不回滚其预占；尚未成功则先提交 SEND_FAILED 拒绝记录，再补偿。之后到达的延迟消息读取拒绝终态，不再使用已经释放的库存落单。

如果此时锁、MySQL或Redis不可用，会返回带订单ID的“结果待确认”，保留预占、记录核对日志，不盲目恢复库存。该异常边界仍需运维核对；本改动没有实现生产者事务 Outbox，也不保证任意长时间数据库／Broker故障下的自动发布恢复。

## 部署：需要协调切换，不能混跑新旧消费者

1. 暂停领券入口，等待在途HTTP请求结束。
2. 停止并确认所有旧版消费者／生产者进程退出，等待在途消费者事务结束。不能仅新增表后直接混合滚动发布。
3. 确认原用户＋券唯一约束已存在，执行 db/migrations/2026-11-09-order-consumption-failure.sql。它只新增表，不修改／删除已有订单。新建库DDL也包含此表。
4. 启动全部新版消费者，再开放新版生产者和领券入口。旧Redis Set可继续拦重复，但其中没有订单归属的失败补偿进入 manual，不凭空推断归属。
5. 先核对小批量领券的订单、失败结果和两个存储的库存，再恢复常规流量。

**回滚限制：** 新版可能已经写下失败终态并释放预占，旧版不认识这些记录，会错误消费延迟消息。不能直接回滚到不读取失败终态的旧消费者。回滚前必须暂停流量、停止消费者，并核对／隔离相应在途、重试和死信消息；优先前向修复，保留新增表和失败记录。

## 核对与恢复

```sql
SELECT order_id,user_id,voucher_id,reason,retained_order_id,
       compensation_status,create_time,update_time
FROM tb_voucher_order_failure
WHERE compensation_status IN ('pending','manual')
ORDER BY create_time;
```

pending 先检查相同订单ID的成功记录、MQ重试和DLQ，再通过现有Broker运维工具以**原订单ID、用户、券**重新投递。重复投递会继续原拒绝结果的幂等补偿，不会创建新订单。这里没有额外引入调度框架。

manual 必须先核对真实订单、Redis库存及资格归属，修复有证据的数据后才可把该记录改回 pending 并重投；不要只把状态改成 done 来掩盖问题。不得删除有效订单的资格。库存原本不一致时，退回本次预占只是撤销一次操作，不等于自动修复此前所有库存差异。

## 验证口径

真实MySQL／Redis／RocketMQ：重复10次、并发、提交后未ACK的重放、实际Broker锁冲突重试、提交暂停期间持锁、插单异常回滚、库存不足后补库存重放、补偿后状态回写失败、归属变化、保留原订单资格、遗留数据、发送响应丢失及延迟送达。

提交后未ACK通过“生产服务先完成真实提交，再重投相同消息”覆盖等价业务边界；没有将它描述成已杀掉并重启整个应用进程的实验。

接口、前端页面和依赖版本保持现有方案。仅新增必要持久化状态及小范围服务分工，没有增加业务页面或工作流框架。

官方接口依据：[RocketMQ Push Consumer](https://rocketmq.apache.org/docs/4.x/consumer/02push/)、[Spring事务注解](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)、[Redis Lua](https://redis.io/docs/latest/develop/programmability/eval-intro/)。客户端仍为项目既有RocketMQ 5.5.1；Push Consumer使用其兼容的经典Java客户端接口。
