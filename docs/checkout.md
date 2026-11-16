# 到店买单、优惠试算与全额撤销

在现有商户详情点“到店买单”，保存本人消费账单。店主在个人页“我的订单 / 优惠券→消费账单”中按账单号确认消费总额及不参与优惠金额；顾客在同一订单面板选择优惠、试算并确认模拟补付。没有商品购物车，没有接入真实支付、POS或美团清算。

## 升级已有数据库

已有库先完成此前订单生命周期迁移，再在目标数据库以utf8mb4执行：

```text
src/main/resources/db/migrations/2026-11-15-checkout.sql
```

脚本可重复执行，不改写历史订单金额/使用状态，不把历史券自动变成新规则券。新安装直接使用`db/smart-life.sql`，无需另跑此迁移。执行前确认目标库并按项目现有流程备份；不要把重建初始化脚本当增量升级。

## 价格规则

按“门店促销→顾客明确选择的已购代金券→单店平台抵用券”顺序计算，各步门槛以之前扣减后的可优惠余额为准。不参与优惠金额始终原价。金额为整数分；折扣采用需付基点（9000为九折），优惠向下取整到分，满减`cap=0`，折扣可设封顶。

每类最多选一项；免费促销可自动择优，付费券不会被自动消耗。参与组合的各方均须允许叠加。自动选择以本次补付最少为目标，再按明确的优先级、到期时间、ID稳定排序。平台券要求本次至少实付1分，无平台券可零补付，零优惠权益不会消耗。最多20项有效门店促销和每人每店20张平台权益，枚举最多441种组合。

平台券已用但未过期/未撤销时继续占发放额度，为全额撤销恢复预留位置；否则“使用一张→补发一张→撤销原账单”会恢复出第21张，破坏候选上限。店主仅管理本店促销，平台模板与发放/撤销仅管理员可操作；平台券首版仅支持明确单店范围。

例如原消费180元、不参与优惠20元，已购券为80元买100元面值：无促销时本次补付80元、累计现金成本160元；再叠加“满150减20”后，本次补付60元、累计140元。若此时平台券门槛为50元，剩余可优惠基数40元，不能按原消费180元判断达标。

## 已购券兼容

现有创建券接口增加可选字段（不新增另一张创建页面）：

```json
{
  "checkoutEnabled": true,
  "checkoutThreshold": 0,
  "checkoutStackMerchant": true,
  "checkoutStackPlatform": true
}
```

与原`VoucherWrite`其余必填字段一起提交；规则随购券订单捕获快照。启用后必须通过消费账单核销，原直接核销接口拒绝，不能绕过最低消费。未启用或历史NULL规则的券仍支持原核销，不能默默套用新规则。已购券的价格、期限和叠加规则不随模板后改而重写。

## 账单与安全边界

- 账单：DRAFT→CONFIRMED→PAID→REVERSED；未支付可CANCELLED。金额必须商户确认，确认后15分钟支付期。创建请求键与本人/原始金额绑定；同键换负载拒绝。
- 报价120秒有效，修改选项须重新试算。支付只提交报价token，不信任前端应付额；事务内锁账单、所选权益和店铺规则版本，读取最新DB时间并重新计算。金额、规则、归属、使用期或权益状态变化均要求重新确认。
- 模拟补付、券核销、平台权益占用和不可变结算快照同一MySQL事务提交。响应丢失重放返回原账单，不多核销；同券两账单竞争只有一笔成功。代金券选择按完整字符串订单ID分页，不在前50张的券可加载更多。
- 原券退款与消费账单撤销分开。商户全额撤销只退本次补付款、恢复原券及平台权益，不退购券价或面值，不退库存、不延长券有效期。原账单快照永久保留。重复旧操作不能影响恢复后已用于新账单的券。
- 未支持部分消费退款，多张付费券、商品级规则、真实收单/商户分账。部分退款请求明确拒绝；不要把已验证的全额撤销宣传成部分退款分摊。

## 接口与演示

均需当前会话`authorization`头，金额为分、ID为字符串。消费账单与券购买订单分别保存，不共用状态码或金额字段。

| 接口 | 用途 |
| --- | --- |
| `POST /checkout/bills` | `{shopId,total,excluded,requestKey}`保存本人草稿 |
| `GET /checkout/bills` | 本人账单稳定游标列表，limit1..50 |
| `GET /checkout/bills/{id}` | 本人详情及可操作状态 |
| `GET /checkout/merchant/bills/{id}` | 授权本店商户详情 |
| `POST /checkout/merchant/bills/{id}/confirm` | `{total,excluded}`确认/修改未支付金额 |
| `GET /checkout/bills/{id}/options` | 可选优惠；`beforeVoucherId`继续加载已购券 |
| `POST /checkout/bills/{id}/quote` | `{automatic,voucherId?,merchantId?,platformId?}`试算 |
| `POST /checkout/bills/{id}/pay` | `{token}`模拟补付并核销 |
| `POST /checkout/bills/{id}/cancel` | 本人取消未支付账单 |
| `POST /checkout/merchant/bills/{id}/reverse` | `{reason}`全额撤销 |
| `GET/POST /checkout/rules` | 规则读取/写入；写入执行范围权限校验 |
| `POST /checkout/coupons` | 管理员按`ruleId,userId,requestKey`幂等发权益 |
| `POST /checkout/coupons/{id}/revoke` | 管理员撤销未用平台权益 |

正常部署沿用[本地启动](local-development.md)和[Nginx说明](frontend.md)，默认打开`http://127.0.0.1:8080/`。验收专用页面曾使用`18890`静态代理→`18892`真实后端，合成账号及SQL夹具保存在本地忽略目录；不在生产代码加入绕过登录的入口。

开发测试：

```text
mvn verify
npm run check:frontend
npm run test:frontend
```

连接专用MySQL/Redis并设置`SMARTLIFE_INTEGRATION_TESTS=true`、关闭自动任务隔离夹具后，可运行`mvn -Dtest=CheckoutIT,CheckoutRulesIT,CheckoutRacesIT test`。完整回归、边界与截图见[验收记录](validation/checkout.md)。公开规则参考及项目默认选择见[设计](superpowers/specs/2026-11-15-in-store-checkout-design.md)。
