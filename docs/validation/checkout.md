# 到店优惠结算验收记录

实施与页面验收：2026-11-15；最后核对与交付收尾：2026-11-16。此记录描述实际执行范围，不声明穷尽所有故障或具有真实支付资质。远端结果以[当前提交的Build检查](https://github.com/beemines/smart-life/actions/workflows/ci.yml)为准，提交前不预报CI成功。

## 环境与结果

Windows、JDK21、项目现有Boot3.5.16；专用Compose项目`smart-life-checkout`，MySQL8.4/Redis8.4/RocketMQ5.5.0，端口13306/16379/19876/11011。真实浏览器Chrome；验收静态代理18890转真实JAR18892。旧页面从基线`32fad72`的frontend归档在18891运行，使用同一组合成顾客作改前对照。

| 检查 | 实际结果 |
| --- | --- |
| Java单元与打包 `mvn verify` | 67项，0失败/错误/跳过，JAR打包成功 |
| 全量真实依赖集成 | 168项，0失败/错误/跳过，含原MQ、恢复、批量任务及新增结算 |
| 前端 `test:frontend` | 60项，0失败/跳过，新增20项买单客户端/真实Vue渲染回归 |
| `check:frontend` | 21段脚本、137处引用及模板检查通过 |
| Nginx1.30容器配置检查 | syntax ok、test successful |
| 增量迁移 | 已有专用库执行两次成功，历史券规则仍NULL |
| 全新安装 | 另建无网络、无对外端口MySQL临时容器；5张checkout表、2家演示商户及初始规则版本0均实际查询确认 |

完整集成命令与CI相同：

```text
mvn --batch-mode --no-transfer-progress -Dtest=StackUpgradeIT,OrderSafetyIT,RedisIdWorkerIT,OrderConsumptionIT,OrderRequestRecoveryIT,P1BoundaryIT,ShopCacheIT,P1VoucherIT,OrderLifecycleIT,CacheWorkloadIT,VoucherBatchIT,OrderListPlanIT,CheckoutIT,CheckoutRulesIT,CheckoutRacesIT test
```

本地环境显式指向上述隔离数据库，设置`SMARTLIFE_INTEGRATION_TESTS=true`，并关闭order recovery/sync/lifecycle/batch自动扫描隔离夹具。集成168项和单元67项分别运行，没有把重跑、跳过和浏览器动作重复计为测试数。235项后端是两组相加，不是235项新的测试。

## 回归与独立审查

- 计算器15项：180元账单、购券成本分离、满减、折扣向下取分、平台最低1分、零收益不消耗、门槛/封顶/叠加双向许可、稳定平局及20×20候选限制。
- 真实HTTP/SQL：草稿键负载绑定、顾客/跨店权限、金额确认、报价替换/过期/金额或规则版本变化、原券快照、历史核销兼容、分单位非法值、全额/部分撤销边界和稳定分页。
- 竞争与故障：两账单争同张已购券或平台权益只有一笔结算；持有券订单行锁并让使用期在等待时到期，支付被拒；在权益已改、账单尚未提交时注入异常，全部回滚，原报价可重试；撤销恢复后再用于另一账单，旧支付/撤销重放不影响新结算。
- 独立代码审查提出3项P2，均先复现再修复：已用未过期权益预留恢复额度；外人真实券订单号与不存在ID统一不泄漏；超过50张已购券可游标加载并选择较早合法券。对应Java与前端回归已通过。
- 实测发现JSON小整数重读为IntNode、计算结果为LongNode，原节点比较误拒绝报价；改为类型化Calculation值比较，独立快照测试覆盖等价值及被改金额。
- 实测页面发现旧商户接口数字ID与新组件字符串契约冲突；改用URL原始字符串。Vue回归用超过JS安全整数的编号证明不经数字转换，并复现非法三位小数拒绝、合法金额提交正确ID。

保留既有全局RuntimeException的HTTP200/`success=false`返回契约；故障注入验证失败正文和实际事务回滚，未为了测试改动所有旧接口的HTTP状态。

### 远端首次失败与MQ等待预算修正（2026-11-16）

首个提交`c4eb263`的[CI](https://github.com/beemines/smart-life/actions/runs/43806325658)中，67项单元/前端/Nginx通过，168项集成中原有`allHttpIntentsDeferredBeforeFirstPublishStillDrainThroughRealMq`超时，其余167项通过。真实日志定位到订单`660606626285223959`：04:51:06.166Z因用户锁竞争返回重试，04:51:45.643Z以`delivery=1 result=CREATED`完成，间隔39.477秒；原断言仅等30秒，观察到19/20终态就超时，消息没有丢失。

经典[Push Consumer重试](https://rocketmq.apache.org/docs/4.x/consumer/02push/)有Broker延迟，重试主题路由发现还会带来额外等待；不以“首次延迟10秒”等同端到端必在10秒完成。此用例保持45秒主题分配等待，业务排空改为与现有负载核对器一致的60秒有界预算。继续通过真实生产者/消费者恢复PREPARING，全部20条必须达到终态，原身份、只有一份订单、库存/资格核对及超时保留证据断言全部保留；没有跳过测试、直接插单或将PENDING视为完成，也没有修改生产重试策略。修正后本地28项消费回归全部通过，独立审查确认两个时间戳及不变的业务断言、结论通过；最终交付仍以最新提交远端全套CI为验收门槛。

## 实际页面与数据库核对

使用专用SQL合成顾客/店主和已支付券夹具；用户通过原模拟短信登录页面进入。已支付券初始夹具不冒充通过真实支付/真实业务系统创建，原购券/MQ链路由全量集成另行覆盖。

1. 顾客实际填写180元、20元不参与优惠，180.001元被拒，合法输入保存待商户确认账单。
2. 店主在原个人页消费账单区输入账单号、确认金额；顾客从原订单面板选已购80元抵100元券、试算、确认模拟补付。
3. 第一笔账单`660368990039703643`本次补付8000分、累计16000分。店主输入原因全额撤销；撤销记录实际退款8000分，恢复原券。
4. 店主从原规则管理区实际创建“满150减20”，不另建管理页面。第二笔`660370802515902556`选恢复后的同一张券，服务端计算门店减2000分、券抵10000分、本次6000分、累计14000分。
5. 第一次视口设置作用在另一活动标签，检查发现顾客仍为1584宽，未计为手机证据；随后在实际活动标签登录顾客，确认DOM viewport及documentWidth均390，完成选券、试算、模拟补付和刷新，结果保持PAID，无横向溢出。手机不是物理设备测试，未声称触屏硬件覆盖。
6. 最后DB重新核对：第一笔REVERSED/8000，第二笔PAID/6000；券订单`8800000000031`为3且仅绑定第二笔，SQL库存仍7，第一笔只有一份8000分撤销退款。2026-11-16再次查询同样成立。

页面检查中相关标签控制台error/warn查询为空。平台权益的发放/撤销、并发/权限/快照由服务集成和前端渲染/客户端测试覆盖，本轮没有把它们写成全部逐项手工点击；规则表单实际手工覆盖门店满减创建。没有真实钱款或外部商户数据。

## 截图

| 改前/改后 | 图片 |
| --- | --- |
| 改前原订单面板 | [before-orders](../screenshots/checkout/before-orders.jpg) |
| 改后商户详情买单入口 | [after-shop-entry](../screenshots/checkout/after-shop-entry.jpg) |
| 桌面报价 | [after-quote-desktop](../screenshots/checkout/after-quote-desktop.jpg) |
| 桌面结账明细 | [after-paid-desktop](../screenshots/checkout/after-paid-desktop.jpg) |
| 商户撤销 | [after-reversal-desktop](../screenshots/checkout/after-reversal-desktop.jpg) |
| 实际规则管理 | [after-merchant-rules](../screenshots/checkout/after-merchant-rules.jpg) |
| 390px商户界面 | [after-merchant-mobile](../screenshots/checkout/after-merchant-mobile.jpg) |
| 390px顾客选券/报价 | [after-mobile-top](../screenshots/checkout/after-mobile-top.jpg) |
| 390px满减明细及操作按钮 | [after-mobile-breakdown](../screenshots/checkout/after-mobile-breakdown.jpg) |
| 390px结账成功 | [after-paid-mobile](../screenshots/checkout/after-paid-mobile.jpg) |

## 范围及后续

本轮实现到店账单/有限组合/全额撤销，不实现部分消费退款、无限多张付费券、购物车、真实支付或分片/服务拆分。模板缓存需对新规则读取测量，worker独立发布和同资源分片仍为单独实验；不因新增结算而把这些待办一并勾选。
