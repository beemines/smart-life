# 订单故障与恢复验证矩阵

本表整理现有实际代码与测试，避免把“没有汇总”写成“没有实现”。最终本轮测试结果在统一验证后补充。测试仅使用独立 MySQL/Redis/RocketMQ 和合成数据，不对业务库运行。

## 机制说明

- **真实进程退出**：独立 JVM 在指定边界执行 `Runtime.halt`，由后续进程/服务恢复。
- **边界注入**：只替换一个发送、缓存、提交或回写边界；其余数据库/Redis 操作真实。不能称为整台服务宕机。
- **等价重放**：真实事务已经提交，再投递同业务消息，验证重复效果；不能称为消费者进程强杀。
- **竞争控制**：使用同步屏障或暂停真实提交，检查锁、事务和最终记录，不以 HTTP200 或发送完成代替落库。

## 已有覆盖

| 故障点 | 测试位置／方法 | 实际机制与预期结果 | 不能据此宣称 |
| --- | --- | --- | --- |
| Redis 预占后应用退出 | `OrderRequestRecoveryIT.actualProcessExitAfterReservationRecoversWithOneReservationAndOneOrder` | 真实子 JVM 退出，恢复沿用原订单 ID，不二次预扣，最终一单 | Redis 任意丢数据可自动修好 |
| MQ 发送后应用退出 | `OrderRequestRecoveryIT.actualProcessExitAfterSendingRecoversWithoutDuplicatingTheBusinessResult` | 真实发送及子 JVM 退出，恢复／重投后只有一个业务结果 | 覆盖所有 Broker 持久化与保留策略 |
| 发送响应丢失 | `OrderConsumptionIT.lostSendResponseAfterConsumerCommitMustNotReleaseSuccessfulReservation` | 发送边界异常，消费事务已成功，不能错误释放成功订单预占 | 真实网络设备全部故障模式 |
| 延迟送达 | `OrderConsumptionIT.delayedDeliveryAfterUncertainSendUsesTheRetainedReservationExactlyOnce` | 保留未知结果预占，消息晚到仍只扣一次 | 接口返回就是创建成功 |
| 用户锁竞争 | `OrderConsumptionIT.realBrokerRetriesALockConflictAndEventuallyCreatesTheOrder` | 真实 Broker 首次进入重试，释放锁后恰好一单 | 无限重试、DLQ 自动闭环 |
| SQL 提交暂挂 | `OrderConsumptionIT.userLockStaysHeldWhileTheDatabaseCommitIsPaused` | 暂停真实提交，确认用户锁保持到事务完成 | 分布式锁任意失效切换保证 |
| 插单失败 | `OrderConsumptionIT.insertionFailureReallyRollsBackTheDatabaseStockDeduction` | 插单边界失败，数据库扣库存随事务回滚 | 数据库物理损坏恢复 |
| 提交后未 ACK | `OrderConsumptionIT.redeliveryAfterCommitWithoutAcknowledgementDoesNotDeductAgain` | 真实提交后的等价消息重放，只保留一单 | 消费者整进程已强杀验证 |
| 相同消息并发重投 | `OrderConsumptionIT.concurrentDeliveryAndItsRetryConvergeToOneBusinessResult` | 并发消费与后续重试，唯一业务结果 | HTTP 入口有限库存争抢已全部覆盖 |
| 成功／失败终态矛盾 | `OrderConsumptionIT.contradictorySuccessAndFailureRecordsMustNotBeAcknowledgedAsAnOrdinaryDuplicate` | 构造矛盾记录，不能当普通重投 ACK | 所有历史数据可自动改正 |
| 补偿执行后确认失败 | `OrderConsumptionIT` 的补偿回写故障用例；`OrderLifecycleIT.actualProcessExitAfterRedisApplyBeforeSqlAckRecoversOnRestart` | 边界注入及真实子 JVM 两种机制分别覆盖，重跑不多返库存 | 所有人工补库存都安全 |
| 首次发布 SQL 后／Redis 后退出 | `P1VoucherIT`、`VoucherPublicationCrashWorker` | 真实子 JVM 退出，持久发布意图恢复；Redis 重试不覆盖已经变化的库存 | 旧券丢库存允许按模板重新播种 |
| 原文库存缺失或损坏 | `OrderLifecycleIT.corruptRedisMovesToReviewWithoutBlindReseed` | 转人工核对，不根据差额盲目生成库存 | 缺库存已自动恢复成功 |
| 超时／退款队列坏记录 | `OrderLifecycleIT` 的 `*QueueFairness*` 用例 | 持久尝试时间与有界批次避免坏队头饿死后续 | 任意异常都立即自动收敛 |
| 批量处理中退出与租约恢复 | `VoucherBatchIT`、`VoucherBatchCrashWorker` | 保留行与稳定订单 ID，后续恢复不重复发放 | 原始 XLSX 文件可断点续传 |
| 导入尾部损坏／导入中断 | `VoucherBatchIT.staleImportPreservesItsPrefixAndFailsWithoutDispatch` 及混合名单导入测试 | IMPORT_FAILED 明确失败，已暂存前缀不允许发放 | 部分文件已经被当作完整名单执行 |
| 撤权与批量受理 | `VoucherBatchIT.revokedCreatorCannotAcceptANewRecipientIntent` / `revokedCreatorDoesNotAbandonAlreadyAcceptedIntent` | 新受理拒绝；已受理仍用原 ID 核对，不遗弃预占 | 撤权能撤回已经合法完成的交易 |

测试代码：[消费](../../src/test/java/com/bee/smartlife/OrderConsumptionIT.java)、[受理恢复](../../src/test/java/com/bee/smartlife/OrderRequestRecoveryIT.java)、[生命周期](../../src/test/java/com/bee/smartlife/OrderLifecycleIT.java)、[首次发布](../../src/test/java/com/bee/smartlife/P1VoucherIT.java)、[批量任务](../../src/test/java/com/bee/smartlife/VoucherBatchIT.java)。

## 排查与恢复原则

1. 从请求标识／订单 ID 查询受理记录、真实订单与失败终态；批量请求增加任务 ID、原始行号。
2. 新鲜在途请求先等待，未知发送结果保留原 ID，不能根据一次超时释放资格或再建订单。
3. 复用[既有预览与恢复 CLI](../order-recovery.md)；执行只处理预览的 ID，锁内重查并持久审计。
4. `RecoveryExecution.outcome=SUCCEEDED` 只说明恢复操作执行成功；最终订单仍需看真实订单与受理终态，可能仍在 `PENDING`。
5. 结果仍不明、归属丢失、库存损坏、成功失败矛盾时保留 REVIEW/MANUAL，不计入自动修复成功。
6. 大批量压测须保存完整运行清单并等待有界排空，未收敛和人工核对单列。不能仅检查一次恢复 CLI 的默认批次或两处库存相等。

现有操作工具限制批量和在途窗口，这是安全边界，不应为“报告全绿”而关闭。测试记录中的历史数量是当时提交结果；本轮统一回归与后续容量测量分别记录，不混为生产保障。
