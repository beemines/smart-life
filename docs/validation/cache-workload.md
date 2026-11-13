# 缓存功能负载测量

这是实际 ShopCache 调用的有界功能负载，不是 HTTP/JMeter 容量压测，也没有改造前后吞吐提升结论。运行两轮观察波动，保持真实 Redis 分布式锁、缓存、Bloom 和 MySQL 商户查询。缓存读取故障在 CacheClient 边界注入，Redis 锁服务仍可用，不能描述成整台 Redis 服务器宕机实验。

## 环境与方法

Windows / Java 21.0.6，JVM 可见逻辑处理器 32，最大堆 7.93 GiB。本机 Docker 专用 MySQL8.4、Redis8.4，与其他测试隔离合成商户数据。

每轮：预热后128次本地命中；清空两层缓存后64次同店并发；8家店缓存同时过期后128次查询；清空本地并注入读故障后32次查询。记录执行线程内调用耗时P95（不含线程池排队），商户原文SELECT次数与同步元数据检查分开计数。每次断言商户ID和内容正确。

| 场景／轮次 | 请求数 / 线程数 | 商户 SELECT | 同步状态 SQL | P95 ms |
| --- | --- | --- | --- | --- |
| hot-local-0 | 128 / 8 | 0 | 0 | 9.026 |
| cold-one-shop-0 | 64 / 16 | 1 | 1 | 89.379 |
| concentrated-expiry-0 | 128 / 16 | 8 | 8 | 15.636 |
| injected-cache-read-failure-0 | 32 / 8 | 32 | 32 | 102.751 |
| hot-local-1 | 128 / 8 | 0 | 0 | 4.783 |
| cold-one-shop-1 | 64 / 16 | 1 | 1 | 53.447 |
| concentrated-expiry-1 | 128 / 16 | 8 | 8 | 14.439 |
| injected-cache-read-failure-1 | 32 / 8 | 32 | 32 | 79.391 |

## 解读与重跑

热本地命中不回源；同店冷读合并回源；集中失效每店回源一次。缓存读取故障时每次调用回源，说明数据库需要承担额外压力，不能从该结果推断无限并发下仍安全。P95受JIT、机器与容器负载影响，不应用于跨机器宣传。

更新／查询提交竞争、两实例实际广播、漏通知TTL、Bloom同步失败的恢复由 ShopCacheIT 验证；这里不把那些正确性测试冒充性能提升。大规模HTTP压测和整服务宕机容量评估仍属于P2。

设置本地开发文档中的专用服务参数，并启用 SMARTLIFE_INTEGRATION_TESTS=true、关闭常规订单及同步调度，然后执行：

```sh
mvn -Dtest=CacheWorkloadIT test
```

原始机器可读结果：[cache-workload.json](cache-workload.json)。CI 保存 target/cache-workload.json，各次测量数字不要求相同，只断言回源与返回值的行为。
