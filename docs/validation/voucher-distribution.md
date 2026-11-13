# 批量发券验证记录

验证日期：2026-11-13。JDK 21 / MySQL 8.4 / Redis 8.4 / RocketMQ 5.5.0，独立合成账号、商户、优惠券及 Topic。代码基线 `c6942fb`，实现及修正至 `5e29d3d`。支付、退款仍是模拟资金操作。

## 自动回归

- 支持的完整 Java 回归：**179 项，0 失败、0 错误、0 跳过**；`clean verify` BUILD SUCCESS，耗时 7 分 57 秒。
- 前端实际处理函数：**40 项通过，0 跳过**；模板、语法及资源检查通过（20 段脚本、134 处引用）。
- 专用演示 Nginx 实际配置 `nginx -t` 通过；桌面及 390×844 真实浏览器验证完成，最终浏览器 warning/error 日志为空。
- 原有 149 项后端回归保留。新增范围包括批次重复、并发恢复、稳定业务 ID、未知发送结果、独立 JVM 中断恢复、XLSX 结构/格式/资源限制和订单列表执行计划。历史手动批量 Redis 实验不属于本次自动回归，不能在业务库执行。

逐类计数：[test-summary.json](voucher-distribution-evidence/test-summary.json)。复现前先按[本地开发说明](../local-development.md)启动隔离服务、配置连接，停止同库演示应用；仅针对可清理的测试库运行：

```powershell
$env:SMARTLIFE_INTEGRATION_TESTS = 'true'
$env:SMARTLIFE_ORDER_RECOVERY_ENABLED = 'false'
$env:SMARTLIFE_SYNC_ENABLED = 'false'
$env:SMARTLIFE_LIFECYCLE_ENABLED = 'false'
$env:SMARTLIFE_BATCH_ENABLED = 'false'
mvn '-Dtest=*Test,StackUpgradeIT,OrderSafetyIT,RedisIdWorkerIT,OrderConsumptionIT,OrderRequestRecoveryIT,P1BoundaryIT,ShopCacheIT,P1VoucherIT,OrderLifecycleIT,CacheWorkloadIT,VoucherBatchIT,OrderListPlanIT' clean verify
npm ci --ignore-scripts
npm run check:frontend
npm run test:frontend
```

独立 CI 分开执行单元打包和上述 12 组集成测试；具体环境与归档路径以 [workflow](../../.github/workflows/ci.yml) 为准。启动正常演示时恢复调度开关，不能一直沿用测试关闭配置。

## 实际页面及数据核对

页面运行最终打包 JAR，真实 Nginx 转发、MySQL/Redis/RocketMQ；复用个人页商户区域，不增加新页面。

| 场景 | 实测结果 | 截图 |
| --- | --- | --- |
| 改造前商户区域 | 保存原布局，用于比较新增区域的大小和位置 | [原页面](../screenshots/voucher-distribution/before-merchant.jpg) |
| 手工名单含有效、重复和不存在用户 | 4 行逐行保留，2 成功、2 失败；成功表示真实待支付订单已落库 | [任务结果](../screenshots/voucher-distribution/task-results.jpg) |
| 上传单列 XLSX | 25 行中 23 成功、2 失败；按 20+5 分页，重复与不存在用户原因可见 | [导入受理](../screenshots/voucher-distribution/import-accepted.jpg)、[逐行错误](../screenshots/voucher-distribution/row-errors.jpg) |
| 手工 A → 导入 B → 再提交同一 A | 复用原 A 任务，没有多创建任务或订单；前端回归另覆盖 A/B/A 超时重试键 | [最终任务](../screenshots/voucher-distribution/after-tasks.jpg) |
| 仅重试可恢复失败 | 使用同一订单 `659552297713402388` 恢复并落库，最终 1 成功 | [重试前](../screenshots/voucher-distribution/retry-before.jpg)、[重试后](../screenshots/voucher-distribution/mobile-retry-result.jpg) |
| 普通收券用户登录 | 看见自己的待支付订单，无商户批量操作；访问别人的批次返回 HTTP 403 | [本人订单](../screenshots/voucher-distribution/recipient-order.jpg)、[权限](../screenshots/voucher-distribution/recipient-permissions.jpg) |
| 用户付款 | 在原订单弹窗模拟支付，数据库状态 2；实付 8000 分、券面额 10000 分，来自服务端快照 | [支付后](../screenshots/voucher-distribution/recipient-paid.jpg) |
| 手机宽度 390×844 | 表单、任务、长 ID、分页和支付弹窗可操作 | [手机表单](../screenshots/voucher-distribution/mobile-form.jpg) |

**重试页面夹具说明：** 为稳定展示可重试按钮，将一条尚未受理的合成明细设置为带明确原因的临时失败，再通过真实页面调用重试接口，后续真实调度/MQ 落库。此 UI 用例不是实际网络故障注入；发送不确定、进程退出、并发恢复由后端集成测试单独验证。

核对时初始库存 100，25 个成功订单后 MySQL 与 Redis 均为 75；再完成重试订单后，共 26 单，两处均为 74。合成订单遵守默认 900 秒付款期限，后续未付款订单自然超时并返还库存；上述数字是验收时点记录，不是永久固定值。

## XLSX 流式与资源边界

先以有界缓冲计算文件摘要，再以 SAX 回调读取，每 100 行提交持久明细。仅完整解析成功的任务进入 READY；尾部结构损坏时保留不可发放的 IMPORT_FAILED 任务，进程中断留下的 IMPORTING 超时后也明确失败，需要重新导入。调度可以恢复已验证名单的发放，**不承诺原始文件的断点续传**。

上限：压缩文件 5 MiB、单表单列、10000 数据行、单元格 128 字符、ZIP 100 项及声明解压量 20 MiB；共享字符串另有限额。公式、分数、非正数及超过 15 位有精度风险的数字 ID 拒绝；安全整数数字单元格可用，长 ID 应填文本。

96 MiB 最大堆的独立子进程测量：

| 行数 | 压缩字节 | 读取前堆字节 | 堆池峰值之和（字节） | 实际回调行数 |
| --- | --- | --- | --- | --- |
| 100 | 4171 | 17561184 | 19658336 | 100 |
| 1000 | 11803 | 16633904 | 20828208 | 1000 |
| 10000 | 86017 | 16724096 | 24064128 | 10000 |

原始报告：[100](voucher-distribution-evidence/batch-memory-100.log)、[1000](voucher-distribution-evidence/batch-memory-1000.log)、[10000](voucher-distribution-evidence/batch-memory-10000.log)。堆池峰值之和是近似观察量，不是同一时刻峰值或 RSS；不能据此宣称常量内存或生产吞吐量。

## 列表查询与价格

用同用户 10000 单、其中目标状态 10 单的合成数据验证真实列表 SQL。未加目标索引时观察到大范围扫描及排序，随后增加 `(user_id,create_time DESC,id DESC)` 和 `(user_id,status,create_time DESC,id DESC)`：

- 普通列表命中 `idx_order_user_time`，订单表实际读取 21 行，无额外 Sort。
- 指定状态命中 `idx_order_user_status_time`，实际读取 10 行，无额外 Sort。

[完整 EXPLAIN ANALYZE](voucher-distribution-evidence/order-list-queryplan.txt)。这是本机合成夹具的执行计划及单次时间，不是容量压测，不推导 QPS 或固定提升倍数。

原有本人列表/状态/游标及支付、取消、核销、退款回归保留；当前读订单价格快照，没有查模板报价的热点证据，未增加模板缓存。支付测试明确提交篡改客户端金额后仍使用服务端快照，并验证模板后改不重写历史订单。商品购物车满减、折扣叠加及分摊未实现，相关待办保持未完成。

## 审查与返工

开发中纠正了最初一次性保留全名单的实现，改成回调与 100 行持久缓冲；审查补上 A/B/A 幂等键保持和 Excel 安全数字 ID 支持。失败测试先复现再修正，定向复审通过。一次中断测试留下的固定主键夹具碰撞改为独立合成主键及限定清理；一次长时间外部停顿导致的 MQ 用例，在不修改业务或放宽超时的情况下单独及完整回归通过。

旧库先备份并按[操作说明](../voucher-distribution.md)执行两份增量迁移。完整分支审查与最终提交 CI 是发布前的独立门槛，不能以本地绿灯替代。

## 最终修正与复测

整体验查的四项重要问题和两项小问题已复审关闭：日期/时间单元格按显式单元格、列、行和默认样式的真实优先级拒绝；商户撤权后不受理新名单行，已受理订单继续核对；冲突的用户、券、请求或终态记录不计成功；文件按券 ID＋内容摘要保持重选幂等，并复用已有随机键兼容回退。新增失败用例先复现，再修正；最终完整后端为 179 项全部通过。

最终 JAR 重新启动后，真实文件选择器操作 A→B→重新选择 A，A 始终返回任务 `659656794267713607`。A 为 23 成功/2 失败，B 为 1 成功/24 失败，再提交手工名单为 2 成功/2 失败。最终该券只有 3 个任务、26 个订单，MySQL/Redis 库存均为 74；普通收券用户没有商户控制区，真实页面模拟付款后订单 `659657051965752169` 为已支付，服务端快照 8000/10000 分。浏览器最终 warning/error 为空。

最终截图：[任务与重选](../screenshots/voucher-distribution/final-reviewed-tasks.png)、[付款成功](../screenshots/voucher-distribution/final-reviewed-payment.png)；[数据核对](voucher-distribution-evidence/final-browser-results.json)。任务截图中手工行当时仍处在正常异步处理中，随后 SQL 核对和付款页面确认落库，不能将截图时的处理中误写成已完成。前述第一次验证截图保留用于比较。

恢复环境时 Docker 已停止，导致一次演示启动无法连接 Redis；恢复原隔离服务后使用相同最终 JAR 通过上述复测，没有为此修改业务代码。只有明确 IMPORT_FAILED 的同内容文件重导需要刷新整个页面后重选；网络结果不明时保留原任务核对。此恢复操作已在操作说明中明确。
