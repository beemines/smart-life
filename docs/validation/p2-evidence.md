# P2 完成证据与复跑入口

本轮范围是已有购券、模拟支付与核销流程的正确性、可排查性和展示证据。保留终身一人一券规则；没有加入购物车满减、模板长期缓存、分片或微服务。

## 后端验证

运行本地独立依赖并按照 [本地开发说明](../local-development.md) 配置后，使用 Java 21 执行：

```powershell
mvn '-Dtest=*Test,StackUpgradeIT,OrderSafetyIT,RedisIdWorkerIT,OrderConsumptionIT,OrderRequestRecoveryIT,P1BoundaryIT,ShopCacheIT,P1VoucherIT,OrderLifecycleIT,CacheWorkloadIT,VoucherBatchIT,OrderListPlanIT' clean verify
```

该范围包含有边界的真实依赖回归；历史手工批量实验不作为默认自动化测试。故障类型、具体用例和结论边界见 [故障矩阵](fault-matrix.md)。

2026-11-14 最终测试夹具／身份回归修改前的历史 v5 完整 `clean verify`：**193 项，0 失败、0 错误、0 跳过，BUILD SUCCESS，8 分 54 秒**，逐类汇总见 [JSON](results/p2-2026-11-14/backend-summary.json)。首次复跑因 Docker Desktop 与 Redis 停止而连接失败，恢复原容器并健康检查后重新全跑；没有将失败或被中断的运行计为通过。生成 JAR 的 SHA-256 为 `1e5411dade81dec0f61231ab693bdfbacec2ce7bb5d295871853020a4fb0c4af`。

HTTP 测试原始关联清单：同用户 [调用](results/p2-2026-11-14/http-contention-9000062069012-accepted.csv)／[终态](results/p2-2026-11-14/http-contention-9000062069012-terminal.csv)，不同用户 [调用](results/p2-2026-11-14/http-contention-9000062069024-accepted.csv)／[终态](results/p2-2026-11-14/http-contention-9000062069024-terminal.csv)。文件只含合成标识，不保存认证信息。

新增 HTTP 并发核对将每个调用用户、请求键、响应订单 ID 与持久受理、SQL 订单及 Redis 预占绑定，防止“数量正确但用户对应错误”的假通过。生命周期竞争分别构造未支付、已支付和支付截止前后起始状态，逐动作断言响应与最终状态一致。

最终审查后，HTTP 测试的合成认证哈希在创建时设置 15 分钟 TTL，并立即检查剩余期限。专项复跑暴露原测试假设：不同用户也会争抢活动预占锁，其 3 秒等待失败可留下 PREPARING。测试现与同用户场景一样，在首单真实 MQ 落库后仅恢复本券的 PREPARING，保留原 30 秒排空期限和全部身份、库存、10 单创建／90 个终态拒绝断言。HTTP 受理不等于创建，独立汇总受理数、同步业务拒绝数、最终创建数与终态拒绝数；新增正向身份核对用例覆盖受理后终态拒绝。先前完整 193 项结果属于修改前测试版本，并未重跑全量；最终源码仍需远端 CI 全绿。

修改后的专项验证：`mvn --batch-mode --no-transfer-progress '-Dtest=HttpOrderEvidenceTest,OrderConsumptionIT#hundredDistinctHttpUsersCompeteForTenThroughRealMq+sameUserConcurrentHttpRequestsCreateOnlyOneOrderThroughRealMq' test`，**5 项，0 失败／错误／跳过，BUILD SUCCESS，1 分 04 秒**。两项 HTTP 场景分别为 20 个受理、1 单创建、19 个终态拒绝，以及 10 个受理、90 个同步业务拒绝、10 单创建、90 个终态拒绝。修改前两次专项运行分别卡在 96／100 和 98／100 个终态，均未记为通过，其日志、夹具和未完成标识私下保留。

## 压测与最终结果

执行入口见 [JMeter 使用说明](../../scripts/load/GUIDE.md)。三个重复轮次分别测量热查询、首次冷查询及 100 用户争抢 10 份库存；售罄拒绝与系统错误分开统计。逐轮原始 JTL、环境信息和最终数据库/Redis 核对文件随测量报告保存。

压测专用进程关闭周期扫描，避免操作其他保留夹具；真实 HTTP、预占、MQ 和订单消费链路仍执行。该测量不包含登录、短信、支付或恢复扫描开销，不能推导生产容量、前后优化倍数或完整用户交易吞吐。

[本轮测量报告](../performance/2026-11-14-local-http.md)保存三轮九组原始 JTL、硬件/依赖与哈希信息。每轮争抢为 100 请求、10 单落库、90 售罄拒绝、0 系统错误，两处库存均为 0。测量使用先前版本的 runner，原始结果保持不变。审查修复后的离线核对工具共 18 项测试，在普通 Python 和 `python -O` 下均通过，包含大整数、漏请求、矛盾终态、系统错误、优化模式失败保留夹具及热查询预热、环境变量注入隔离、PID 清单写入失败关闭自有进程、依赖超时保留最后完整快照及未完成订单标识、待补偿与人工审核分开归类及跨记录去重。真实负载未模拟依赖挂起，该失败路径由离线故障注入验证；这些检查不是新的性能测量。

## 评分展示与前端

仅在笔记详情原有 `el-rate` 添加 `disabled`，不加提交接口、不改页面布局。真实 Chrome 中，改前商户评分在无障碍树中为可设置滑块，值 4.5；改后为 disabled，鼠标点击、390×844 手机触摸模拟和方向键后仍为 4.5，刷新也保持。原页面本来就没有保存评分的处理，这不是修复可篡改数据库评分的漏洞。

| 场景 | 修改前 | 修改后 |
| --- | --- | --- |
| 桌面 | [截图](../screenshots/rating-readonly/before-desktop.jpg) | [点击与键盘后截图](../screenshots/rating-readonly/after-desktop.jpg) |
| 手机视口 390×844 | [截图](../screenshots/rating-readonly/before-mobile.jpg) | [触摸与键盘后截图](../screenshots/rating-readonly/after-mobile.jpg) |

前端 40 项测试通过，静态检查通过（20 个脚本、134 个本地引用）。手机验证为 Chrome 设备模拟，不能宣称在真实手机硬件上测试。截图保留浏览器控件，方便核对地址和视口尺寸。

## 日志定位

批量任务两个异常边界补充任务 ID、原始行号、已知订单 ID、阶段及异常类型。异常正文、堆栈和请求负载不进入新增 WARN。失败仍保留租约与既有恢复语义，不因为日志新增而直接确认任务成功。

排查时先从任务和原始行取得稳定订单 ID，再查持久受理、真实订单、拒绝记录及补偿记录。`RecoveryExecution.outcome=SUCCEEDED` 仅表示恢复操作执行过，不能代替实际订单终态。见 [恢复说明](../order-recovery.md)。

## 展示材料

- [项目简介、简历条目、架构与面试问题](../project-showcase.md)
- [待办与暂缓范围](../../todolist.md)

本地完整验证、任务审查后才提交；交付时另核对最终提交的三项 GitHub Actions。历史提交的绿灯不能替代最终提交的状态。
