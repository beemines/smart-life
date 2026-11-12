# P1 权限、缓存与库存发布运行说明

本批修复现有链路的八类问题，复用原有页面、MySQL、Redis、Redisson 与 RocketMQ。未新增支付、取消、核销、退款或完整订单列表。

## 旧库迁移

停止旧版应用，备份数据库，先完成之前的订单唯一约束、消费失败记录、受理恢复迁移，再在 MySQL 客户端执行：

```sql
SOURCE /absolute/path/to/smart-life/src/main/resources/db/migrations/2026-11-11-p1-boundaries.sql;
```

新建环境直接使用最新 `smart-life.sql`。已有 Compose 卷不会自动重放初始化 SQL。本次迁移新增四张表：`tb_shop_permission`、`tb_uploaded_file`、`tb_shop_cache_sync`、`tb_voucher_publication`。迁移只把确实关联秒杀明细的旧 `type=0` 券归一为秒杀类型，普通券不改变。

升级需协调切换，不能让缺少新权限／发布校验的旧版本继续写入。历史秒杀券的发布记录禁止重新播种库存：已有合法 Redis 库存可确认，丢失／损坏则转 REVIEW，不能把数据库库存直接覆盖回 Redis。

## 显式授权

公开商户、分类、券列表和热门笔记的 GET 保留公开读取。商户创建限管理员；商户修改、发券及上下架限本店店主或管理员。服务端从登录上下文取身份，字段白名单忽略客户端伪造的统计值、角色和服务端字段。

由有数据库管理权限的部署者核实账号和商户后显式授予权限，例如：

```sql
-- 用真实核实过的 ID 替换占位参数，不要给所有已有用户授权。
INSERT INTO tb_shop_permission(user_id, shop_id, role)
VALUES (:administrator_user_id, 0, 'ADMIN');
INSERT INTO tb_shop_permission(user_id, shop_id, role)
VALUES (:owner_user_id, :owned_shop_id, 'OWNER');
```

上述 `:name` 是说明占位符，不能原样粘贴到 mysql。没有自助提权接口，会员 level 不等于管理员。核销功能还未实现，其权限规则也不计为完成。

## 缓存一致性与恢复

商户 SQL 写入和同步版本在同一事务提交。更新持有每店锁直到提交后失效；冷读持同把锁完成 Redis 与最终 Caffeine 填充。同步未完成时先检查持久记录并读 SQL，避免命中旧正值／空值。

提交后更新 Bloom、清理共享和本机缓存，再通过 RocketMQ **BROADCASTING** 向各实例通知本地失效。重复通知仅重复失效。默认本地 TTL 为写入后 3 分钟，漏通知时以此作为本地陈旧上限；这不是强一致读承诺。后台每 5 秒重试待同步记录，失败记录有 15 秒冷却，保存 attempts、last_error 与最后尝试时间。

检查 `tb_shop_cache_sync` 中 `version > synced_version` 的记录及 last_error。应恢复 Redis／MQ 连接后让原任务继续，不要手工把未完成版本标成已同步。未做缓存性能基准，不能把功能回归写成 QPS／P95 提升。

## 秒杀首次发布和时间规则

券、SQL 库存和发布意图一起提交后才调用 Redis。新券可首次填入初始库存；发布重复、响应丢失或进程重启均不重置已存在计数。SQL ack 失败由原发布记录继续重试。发布未就绪的券不可领取。

上下架、新预占和发布共享活动锁；传给 Lua 的规则来自服务端持久记录。Lua 使用 Redis TIME 判断 `[begin,end)`，默认活动业务时区为 `Asia/Shanghai`；审计 TIMESTAMP 仍采用 UTC 会话。已归属同一订单的有效在途预占允许继续处理，不因后来结束／下架而误补偿。

检查 `tb_voucher_publication`：PENDING 可由任务恢复；REVIEW 表示缺失／损坏等需核对，不能盲目重新加库存。`SMARTLIFE_SYNC_ENABLED=true` 默认启用缓存及发布恢复任务，正常运行不要关闭。

## 图片与退出登录

上传只接受真实 PNG/JPEG，后缀、MIME、实际格式一致，单文件最多 5 MiB、单边最多 8192 像素、总像素最多 2000 万；解码前检查尺寸，通过后重新编码。Nginx 请求上限 6 MiB，为 multipart 留出开销。

上传返回 `/blogs/...` 原始相对名称，前端仅展示时拼 `/imgs`；DELETE 使用原始名。服务器校验路径、真实父目录、文件状态和上传人。其他用户不能删除；已删除记录可重复请求。旧文件没有归属记录时不猜测拥有者，不提供任意路径删除。文件和 SQL 不是分布式原子事务，删除失败保留可重试记录，不能声称任意外部磁盘修改都自动恢复。

`POST /user/logout` 删除当前 Token 的 Redis 会话，前端清理 Token／用户缓存／受理缓存。重复退出安全，不退出其他设备。续期失败或会话不存在时不将身份写入 ThreadLocal，请求入口和结束均清理残留身份。

验证命令、真实页面截图及边界见 [P1 验证记录](validation/p1-boundaries.md)。
