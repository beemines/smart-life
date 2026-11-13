# 按名单批量发券

批量发放的是待支付券订单，用户付款后方可使用。它沿用已有一人一券、超时关闭、取消、全额模拟退款与核销规则；不是免费赠券。操作入口复用个人页“我的订单 / 优惠券”中的商户区域。

## 操作流程

1. 由部署者核实并配置现有 OWNER／ADMIN 权限，普通用户不能自行提升权限。
2. 准备已发布、上架且处于领取期的秒杀券；填写券 ID。
3. 粘贴用户 ID，每行一个；或选择 `.xlsx` 名单。Excel 首行 `userId`，单工作表、单列，ID 建议为文本。查看导入限制和逐行错误，不要把含公式或精度丢失的数字当作可信用户 ID。
4. 提交后保留任务号，刷新查看待处理、处理中、订单已创建与失败的明细。订单已创建仍需用户付款，不代表支付完成。
5. 只对可恢复的失败项重试。未知发送结果保留原业务 ID 核对；用户不存在、重复领取等永久拒绝不会被无限重试。

成功／失败数量来自持久化明细；刷新或进程重启后可继续查询。重复名单行保留原行号，不能为了统计整齐而默默丢弃。

## 接口约定

接口均需登录并按券／任务的真实商户归属检查权限。JSON ID 为字符串。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/voucher/batches` | 手工名单：voucherId、requestKey、userIds |
| POST | `/voucher/batches/import` | multipart：voucherId、requestKey、file |
| GET | `/voucher/batches` | 任务游标分页 |
| GET | `/voucher/batches/{id}` | 任务汇总及逐行明细分页 |
| POST | `/voucher/batches/{id}/retry` | 重试可恢复失败项 |

提交、导入、重试均返回任务对象。`requestKey` 只用于同一负载重试，同一 key 不能改变券或名单。Excel 请求由浏览器生成 multipart boundary，无需手工指定 Content-Type。

浏览器对不超过 5 MiB 的文件计算 SHA-256，以券 ID＋文件内容保持本页重试标识；重新选择同一内容不会因新的 File 对象而重复建任务，同名同大小但不同内容也不会混用。导入需通过 HTTPS 或 localhost 访问，以使用浏览器摘要 API；不可用时页面会明确提示且不上传。手工名单复用项目既有随机请求键及兼容回退。

## 范围

本人订单列表、状态筛选和游标分页复用已有生命周期接口。订单金额、商户和使用期限以订单快照为准，模板后改不应影响已经创建的订单。固定价格购券不等同于商品购物车满减；当前没有新增商品订单、叠加券或真实支付渠道。

## 旧库与调度

已有开发库先备份并停用旧应用，执行 [批量任务迁移](../src/main/resources/db/migrations/2026-11-13-voucher-batches.sql) 和 [列表索引迁移](../src/main/resources/db/migrations/2026-11-13-order-list-indexes.sql)。新库结构脚本已包含对应结构；不要重建已有业务表。

`SMARTLIFE_BATCH_ENABLED` 默认 true。隔离测试设为 false 并显式调用恢复入口，正常演示保留开启。扫描每批最多100条，失败有持久重试信息和冷却；实例间使用有期限的明细处理租约，订单侧继续使用原锁与幂等记录。发送结果未知时保持处理中，订单真实落库后才显示成功。

Excel 先以有界读取计算文件摘要，再按行解析，每100条提交暂存明细；完整校验后才允许发放。解析中断或结构错误的任务不能发放部分名单。长时间停留在导入中的任务会转为明确失败，保留任务号与原因；不保存原始文件供自动续传，需按提示重新导入。导入失败后可以刷新任务查看记录。

用户 ID、名单和原始文件应仅用于业务所需的发放；任务查询按真实商户权限校验。不要把生产用户名单用于仓库里的合成数据测试。

完整命令、自动回归、真实页面截图及资源测量见[验证记录](validation/voucher-distribution.md)。

## 库接口依据

Excel 实现前核对了 Apache POI 的[发行说明](https://poi.apache.org/download.cgi)、[XSSF 事件读取方式](https://poi.apache.org/components/spreadsheet/how-to.html)、[XSSFReader API](https://poi.apache.org/apidocs/dev/org/apache/poi/xssf/eventusermodel/XSSFReader.html)和 [ZIP 读取保护](https://poi.apache.org/apidocs/dev/org/apache/poi/openxml4j/util/ZipSecureFile.html)。流式读取不等于所有内存恒定，还需要限制共享字符串、单元格、行数和展开内容，并以实测说明边界。

浏览器文件摘要按 [SubtleCrypto.digest](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/digest) 和 [Blob.arrayBuffer](https://developer.mozilla.org/en-US/docs/Web/API/Blob/arrayBuffer) 接口实现；摘要 API 本身不流式，前端只读取已限制的 5 MiB 文件，不缓存文件内容。
