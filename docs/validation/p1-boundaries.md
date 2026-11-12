# P1 八项修复验证

验证日期：2026-11-11 至 2026-11-12。范围、方案与逐项计划见 [设计](../superpowers/specs/2026-11-11-p1-boundaries-design.md)、[计划](../superpowers/plans/2026-11-11-p1-boundaries.md)。

## 环境和结果

Windows、JDK 21、Spring Boot 3.5.16；独立 `smart-life-upgrade` Compose 环境：MySQL 8.4、Redis 8.4、RocketMQ Broker 5.5.0／Client 5.5.1。仅使用合成账号和业务数据，未修改其他项目服务。页面通过 Nginx 1.30 容器访问实际打包 JAR，没有伪造浏览器响应或 DOM。

| 检查 | 结果 |
| --- | --- |
| 全量 Java 单元与八组真实集成测试 | **127 tests，0 failures，0 errors，0 skipped**，`clean verify` 成功 |
| 实际前端处理函数测试 | **17 tests 全通过**，包括异步删除错位和服务端错误消息显示 |
| 前端资源／JS／Vue 模板 | 18 段脚本、130 处引用检查通过 |
| 实际 Nginx | 配置 `nginx -t` 通过，静态页、API、图片代理可用 |
| 大图片经真实 Nginx 上传、展示、删除及重删 | 1,441,619 字节合法 PNG，通过；无 1 MiB 默认限制误拦 |
| 实际浏览器 | 登录、图片上传展示／删除、伪图片拒绝、真实 MQ 落单、旧页面领取下架券被拒、退出后跳回登录，全部通过 |
| 独立代码审查 | 完成一次全分支审查；采纳的重要问题逐个补 RED 回归并修复后跑全量 GREEN |

完整后端运行命令（先启动专用服务，切勿指向已有业务库）：

```powershell
$env:JAVA_HOME = '你的 JDK 21 目录'
$env:SMARTLIFE_DB_PORT = '13306'
$env:SMARTLIFE_DB_PASSWORD = 'local-test-only'
$env:SMARTLIFE_REDIS_PORT = '16379'
$env:SMARTLIFE_MQ_NAME_SERVER = '127.0.0.1:19876'
$env:SMARTLIFE_INTEGRATION_TESTS = 'true'
$env:SMARTLIFE_ORDER_RECOVERY_ENABLED = 'false'
$env:SMARTLIFE_SYNC_ENABLED = 'false'
mvn --batch-mode --no-transfer-progress clean verify '-Dtest=P1VoucherIT,OrderConsumptionIT,OrderRequestRecoveryIT,StackUpgradeIT,OrderSafetyIT,RedisIdWorkerIT,P1BoundaryIT,ShopCacheIT,*Test'
npm run check:frontend
npm run test:frontend
```

集成测试关闭常规调度以隔离夹具；测试中的恢复验证自行启动任务。正常演示需恢复 `SMARTLIFE_SYNC_ENABLED=true`，不能照抄测试隔离开关后以为恢复任务在工作。

## 回归证据

- **权限与身份：** 匿名、普通用户、本店／跨店店主、管理员；写字段白名单；公开 GET／尾斜杠兼容；Token 撤销、重复退出、请求续期异常不残留线程身份。
- **文件：** 真 PNG/JPEG、空文件、错误 MIME／后缀、伪图片、超大小／尺寸；原始 multipart 流关闭；所有者删除、他人／越界路径拒绝、重复删除、非 ACTIVE 不误删；Windows junction 父目录逃逸回归也实际执行，未跳过。
- **缓存：** 新店立即可见、Bloom 写失败恢复、旧空值不掩盖 pending；通过真实提交门闩和 Redis 锁控制冷读竞争，验证返回值及最终两层缓存；两个独立 Spring 上下文、真实 MQ 广播、重复通知和 TTL 兜底。
- **活动和发布：** 券不存在、下架、开始前、结束后、开始包含／结束排除；同订单有效在途预占继续处理；SQL 提交失败不发布、Redis 失败保留 pending、重复发布不重置库存、旧券不播种、库存丢失转 REVIEW。
- **实际进程崩溃：** 测试子 JVM 在 SQL 已提交／尚未写 Redis，以及 Redis 已写／SQL ack 前分别 `halt(74)`，再由重启进程调度恢复。不是用普通抛异常冒充进程退出，生产 JAR 不含这些测试钩子。
- **原有 P0：** 保留 ID、唯一约束、并发重投、提交后 ACK 前退出、受理持久化和补偿恢复测试。

独立审查补出了身份泄漏、原始输入流关闭、旧秒杀类型迁移、关联商户不存在、非 ACTIVE 文件误删、链接目录预创建以及前端异步索引错位；均先观察失败，再修复并全量重跑。没有追加无止境的重复审查。

## 实际页面与数据库核对

演示应用的 JAR SHA256：`7d72753dc0933971a64acc4da2c623e35583a0176c4e68075df4339dd6d24fb5`。Nginx 测试端口 18180、后端 18181，演示数据不是正式商户或真实支付。

1. 浏览器登录合成账号，发笔记页上传真实 PNG，缩略图正常；点击原有删除按钮后缩略图消失。
2. 选择文本伪装的 `fake.png`，页面明确提示“文件不是有效图片”，未生成缩略图／归属记录。
3. SQL 核对该账号只有两条合法图片记录（分别来自 Nginx 大文件验证和浏览器上传），状态均 DELETED；两条原磁盘路径均不存在。
4. 正常券 624 从库存 2 变为 1，真实消费者创建订单 `659101716989345793`，页面从“已受理，处理中”变为“订单创建成功”。
5. 页面保留券 625 的可抢购按钮时，通过真实受保护状态 API 下架，再点击旧按钮，页面提示“优惠券不存在、未上架或尚未准备就绪”；数据库没有该券的订单、库存仍为 2。
6. 在原个人页退出，再直接访问个人页，真实跳转到登录页。

### 前后截图

原布局截图使用基线 `f07d6c6` 的前端静态文件；其 `/api` 仍连接本轮后端，仅用于对照布局，不作为旧后端行为复现证据。

| 页面 | 截图 |
| --- | --- |
| 原发笔记布局 | [before-editor.png](../screenshots/p1-boundaries/before-editor.png) |
| 新版真实上传显示 | [after-upload.png](../screenshots/p1-boundaries/after-upload.png) |
| 删除后 | [after-delete.png](../screenshots/p1-boundaries/after-delete.png) |
| 伪图片错误提示 | [fake-image-rejected.png](../screenshots/p1-boundaries/fake-image-rejected.png) |
| 真实订单创建成功 | [order-created.png](../screenshots/p1-boundaries/order-created.png) |
| 旧页面领取下架券被拒 | [down-voucher-rejected.png](../screenshots/p1-boundaries/down-voucher-rejected.png) |
| 退出后受保护页跳转 | [logout-redirect.png](../screenshots/p1-boundaries/logout-redirect.png) |

## 验证边界

本批完成现有领取路径的有效期和权限检查，不代表支付／核销阶段已经存在。缓存回归证明受测提交交错与多实例通知有效，没有测热点回源 P95／QPS，不填写性能提升数字。持久同步不能替代 Redis 数据丢失后的库存业务核对，不能盲目重建库存。

远端最终提交与三项 CI 结果将在发布核对后追记。
