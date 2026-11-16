# 单库容量与批量导入干扰实测（2026-11-15）

本次只创建临时实例、合成数据与报告；没有实现分库分表、服务拆分或独立发布。结论仅适用于本次受限环境。订单列表保留现有索引，在百万订单下仍使用范围扫描与主键关联；修正后的容量观察见下表。批量导入会争用资源，限并发是可直接验证的控制手段；独立 JVM 的收益需要结合共享数据库和额外资源成本判断。

## 环境、数据与执行边界

- Windows / JDK21；Docker 29.8.1 Linux VM 可见32逻辑CPU、16,599,089,152字节内存，宿主机32逻辑CPU、34,024,747,008字节物理内存（根代理提升权限只读观测；本子任务sandbox直接CIM被拒）。客户端与数据库在同一台机器，存在共享主机资源与Windows/Docker转发开销。
- 唯一MySQL容器 `sl-capacity-20261115-13416`，MySQL8.4缓存镜像，`127.0.0.1:13416`；2CPU、1536MiB内存，memory+swap总额同为1536MiB（无额外swap）、128MiB InnoDB buffer pool、100连接上限。CPU控制仅改为4CPU，最后恢复2CPU。新密码仅为本实验公开的临时口令，无用户环境密钥。
- SQL数据10万→100万订单；每用户严格100订单，对应100万生命周期快照、10万库存归还记录。合成时间固定2026-01-01，每用户100种券、6种状态，快照含店名/券名/金额/期限，无真实用户数据。订单数据和索引约183MB、生命周期含索引约147MB（information_schema估算），工作集与缓存关系不能代表线上分布。
- 从仓库DDL复制列和全部索引；包括 `uk_order_user_voucher`、`idx_order_user_time(user_id,create_time DESC,id DESC)` 与 `idx_order_user_status_time(user_id,status,create_time DESC,id DESC)`。不移除索引、不引入SLEEP/OFFSET或刻意长锁。
- Java独立JDBC客户端，缓存Connector/J9.7.0，无安装依赖。真实 `OrderLifecyclePersistence.SELECT` 两个LEFT JOIN，追加 `OrderLifecycleService.mine` 的用户、可选状态、时间/id游标及LIMIT21。随机用户，正常/状态过滤各约一半，游标位置50或100；读取全部返回列，未执行DTO/鉴权/Redis/HTTP等完整业务。
- 旧无效实验每规模先2秒预热；有效重测在百万订单上先2秒数据库预热，两轮1/8/32/64并发，各5秒闭环读取，无思考时间；第二轮倒序。每个profile启动新JVM，因此预热不包含该JVM的JIT稳定过程。连接建立在计时屏障之前；查询超时3秒、连接3秒、socket5秒；线程总并发≤64。
- SQL批量实验：同库8读者，0/4/1导入worker，两轮各10秒，第二轮逆序。每100行事务锁自己的父任务、逐行执行真实去重EXISTS，再批量INSERT；每父任务最多10000行，不制造同一父锁热点。模拟 `VoucherBatchService.stage` 的SQL部分，未测Redis发号/XLSX解析/MQ或后台履约。

## 独立复核发现与有效证据边界

独立复核确认严重测量错误：旧客户端用Timestamp.valueOf在JVM Asia/Shanghai构造时间，再向UTC数据库setTimestamp，游标实际变为前一天16点；旧SQL workload所有读取均返回0行，原客户端只校验≤21未校验非空。**此前全部JDBC吞吐、时延、CPU控制及前台干扰结果均不能代表有数据的订单分页，已从本文有效表格和结论撤除。** 原文件保留在原位，旧汇总/客户端副本及INVALID标记见invalid-empty-page-pilot。此前还有一次汇总覆盖事故，均在ledger披露，不能用覆盖前的时延样本挽救空页实验。

先运行相同PreparedStatement绑定的微型核验：旧绑定普通/状态页均0行；LocalDateTime.setObject修正后普通页21行，用户5000状态2在游标50/100分别8/16行，与相同字面量SQL一致。JVM时区/时间instant/DB时区与精确行数见bind-check-red-green.txt、BindCheck.java。随后仅修正scratch客户端，显式SET SESSION time_zone='+00:00'，使用与业务相同的LocalDateTime绑定，每次读取逐一断言合成数据的精确预期行数且禁止0行。

下表仅采用新的utc-* profile与utc-results.json，保留每次真实参数(user/status/cut)、actual_rows/expected_rows和逐profile capture/summary；新的tag避免复用旧批次。这里只重测百万订单，没有有效的10万性能基线，不能据此声称已证明数据增长曲线。两个规模的字面量EXPLAIN仍有效，但与旧错误绑定不是同一工作量。HTTP每次断言20条数据，未受JDBC游标错误影响，沿用修正客户端后的独立HTTP结果。

## 百万订单重测：混合正常/状态过滤的SQL读取

|并发|轮|实测SQL读/s|P50 ms|P95 ms|P99 ms|错误|物理读页增量|cgroup限流period增量|
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|1|1|403.4|2.31|3.97|5.05|0|6616|0|
|8|1|1335.7|5.35|9.21|15.68|0|21051|38|
|32|1|1474.4|5.51|81.00|84.73|0|22600|46|
|64|1|1360.9|12.94|92.59|115.84|0|19902|43|
|64|2|1336.0|15.14|95.12|123.72|0|18843|45|
|32|2|1258.7|7.22|81.54|85.85|0|17530|45|
|8|2|1439.3|4.86|8.84|14.96|0|19783|38|
|1|2|404.8|2.32|3.76|4.62|0|5520|0|

`EXPLAIN ANALYZE`在两个数据规模下均保留；正常列表使用idx_order_user_time，状态列表使用idx_order_user_status_time，生命周期/库存归还均主键单行查找，未见全表排序。每用户只有100行，百万总行数不等于每次扫百万行。正常查询最多21行，状态筛选结果更少，因此混合读取率不能当同一行数的吞吐比较；每类原始时延可由CSV首列区分。CSV列依次为kind,latency_ms,actual_rows,expected_rows,user_id,status,cut_seconds；正常页必须21行，状态页逐条按种子分布计算预期8/9/16/17行。

CPU控制：同一数据、索引、缓存大小、64读者，只把容器CPU2改为4；最终恢复2。重测控制在运行中修改CPU，没有重启；初次CPU4控制属于已判无效的空页pilot，只作失败审计保存，未参与本表或结论。两轮控制结果如下。

|CPU限额|轮|SQL读/s|P95 ms|P99 ms|物理读页增量|限流period增量|
|---:|---:|---:|---:|---:|---:|---:|
|2|11|1360.9|92.59|115.84|19902|43|
|2|12|1336.0|95.12|123.72|18843|45|
|4|17|1740.9|73.28|86.36|24440|41|
|4|18|1666.2|75.63|91.75|23701|43|

CPU配额限流计数、Docker瞬时CPU采样及CPU控制共同用于判断受限实例的压力，不用P95单独推断数据库瓶颈。capture间隔还含客户端启动/采样开销，cgroup累计CPU不能直接除以5秒声称精确利用率。客户端CPU秒数/堆使用量、DockerCPU/内存/BlockIO均在完整utc-results.json中；客户端新JVM/JDBC解码也会限制吞吐。没有证明磁盘带宽或锁等待是主导瓶颈，也没有测生产SLO、长期稳定性或增长极限。

## 同CPU下的buffer pool控制

额外只改变InnoDB buffer pool：2CPU、1536MiB容器上限不变，128→512MiB。在线SET GLOBAL后确认实际字节数与resize完成；两个buffer条件都先运行同样5秒32-reader数据库预热，再各做两轮64-reader五秒采样。客户端仍每profile新JVM，预热并非JIT稳定保证；比较受短采样与工作集分布限制。结束恢复128MiB与2CPU。

|buffer MiB|轮|SQL读/s|P95 ms|P99 ms|物理读页增量|限流period增量|
|---:|---:|---:|---:|---:|---:|---:|
|128|21|1107.5|102.52|509.37|17461|43|
|128|22|1306.9|92.59|118.13|19352|43|
|512|23|1502.6|90.09|111.02|1574|44|
|512|24|1687.7|88.17|95.74|397|44|

物理读页计数下降只能证明此工作量下缓存命中改善；不能直接推断硬件IO已饱和。CPU与缓存容量都是先于分片的可测垂直资源选项。动态resize依据[MySQL官方文档](https://dev.mysql.com/doc/refman/8.4/en/innodb-buffer-pool-resize.html)，原始确认见buffer-confirm-*.txt。

## SQL读与批量stage干扰

|批量worker|轮|SQL读/s|读P95 ms|读P99 ms|stage事务/s|stage P95 ms|错误|锁等待增量|
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|0|1|1862.3|7.68|11.47|0.00|0.00|0|0|
|4|1|1320.5|25.49|36.23|13.01|388.01|0|0|
|1|1|1412.0|12.24|24.67|3.56|460.48|0|0|
|1|2|1645.2|9.35|19.95|4.08|386.52|0|0|
|4|2|1234.3|27.86|36.96|11.92|506.17|0|0|
|0|2|1781.7|7.52|12.65|0.00|0.00|0|0|

一个stage事务成功插入100行，所以事务/s×100才是模拟stage行/s，不能称完整发券业务TPS。限批量并发会牺牲后台完成速度，观察前台尾延迟与完成速度的取舍。父任务互相独立，若锁等待为零仍可存在CPU/IO/连接资源竞争，不能把一切干扰称为行锁。

## 实际HTTP角色放置实验

在同一百万订单库和新Redis `sl-capacity-redis-20261115-16416`（localhost16416，128MiB/1CPU）下启动同一个已有JAR的两个独立JVM，端口18196/18197，每个Xms256m/Xmx512m，保持项目默认Tomcat线程与Hikari10。清除用户业务环境变量并显式指向新DB/Redis；禁用消费、恢复、批次周期任务、生命周期扫描、同步、缓存广播，MQ nameserver指向本地未监听19999。无修改应用代码；JAR哈希及PID保存在http-environment.json。辅助字段http-results.json里的drain_s在采集DB后才计算，实际包含drain及采集耗时；原始字段不改写，不能当纯排空时间。duration_s在采集前计算，公开速率不受此字段影响。客户端使用每线程持久http.client.HTTPConnection，直接访问localhost，无代理、SSL上下文或每请求opener构造。初轮客户端每请求build_opener可能重复加载Windows证书存储，基线P95约186–209ms；该轮完整保存于http-pilot-client-setup，作为客户端受扰pilot，不用于应用容量结论。修正后使用v2请求键重新真实导入，避免幂等命中旧批次。

真实GET `/voucher-order/mine?limit=20`，4个闭环读客户端，合成会话固定用户1，故是热点用户及真实DTO/Redis/权限路径，不能与SQL随机用户吞吐直接比较。真实multipart POST `/voucher/batches/import`，字段voucherId/requestKey/file，标准XLSX每文件1000用户、100行一事务，最多8文件/profile，连续15秒启动窗口。原计划10000行文件因SQL阶段观测到事务耗时，为确保20秒请求超时边界改为1000行，仍处于生产10000行限制内。

场景：baseline无导入；same8在A上8导入worker；same1在A上限1；split8在B上8worker、A读取，数据库与Redis共享。两轮固定顺序；每profile等待客户端futures完成，并检查HTTP批次IMPORTING归零，最多20秒drain；完成后才进入下一profile。分位数基于成功读取，失败单列，绝不把失败当快速成功。限1时未必在15秒内完成8文件，所以它代表准入取舍，而非相同后台完成量下的免费收益。

|场景|轮|成功读/s（含drain窗口）|P50 ms|P95 ms|P99 ms|完成导入文件|导入文件/s|导入P95 ms|失败|
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
|baseline|1|366.71|9.77|15.93|19.73|0|0.000|0.0|0|
|same8|1|282.26|12.07|24.21|34.13|8|0.513|11031.4|0|
|same1|1|398.85|8.31|11.68|15.04|4|0.231|4412.5|0|
|split8|1|360.11|9.05|18.19|24.28|8|0.509|9294.7|0|
|baseline|2|515.56|7.15|9.23|12.23|0|0.000|0.0|0|
|same8|2|446.14|7.41|14.67|19.16|8|0.513|5928.9|0|
|same1|2|440.97|8.00|10.15|12.29|4|0.243|4101.7|0|
|split8|2|398.87|7.98|16.40|19.49|8|0.513|7357.2|0|

修正客户端后，baseline两轮P95为15.93/9.23ms，same8为24.21/14.67ms，split8为18.19/16.40ms；分进程第一轮改善、第二轮略差，没有稳定优势。same1为11.68/10.15ms，但每轮仅完成4文件，same8/split8各完成8文件。因此前台改善同时伴随后台完成速度下降。两轮baseline差异也表明JIT/运行顺序与短采样影响明显，不把这些读/s当生产容量承诺。

表中导入文件/s按整个profile墙钟时长计算，不是只截取后台活跃区间；每个场景最多8个样本时导入P95取最近秩即最大值。

HTTP的JVM CPU秒、工作集与私有内存前后快照、DB状态/cgroup计数、每次导入耗时与任务ID、读取原始时延均保留。HTTP完成数与耗时是阶段导入指标，不是最终发券成功数。两个JVM在所有场景都启动，但split实际把计算、线程和连接池分散到第二进程，扩展了活跃资源；并非等CPU/等连接池预算的隔离收益证明。单用户热点、短采样、固定顺序、Python线程/JSON处理、未稳定的JIT/GC及附带采样成本都可能影响变化。未执行MQ消费与发券履约，不得称完整下单吞吐；也没有建立两个独立业务服务/API契约或发布流水线。

## 解释与下一步

这份数据支持先围绕现有SQL索引、CPU/缓存容量、在线与批量准入做容量管理；不能据此推出当前生产必须分库分表。拆进程仍共用数据库，无法消除共享DB的争用。HTTP是否改善读P95应逐轮看表，结果波动时不能声称稳定收益。独立部署/扩容的资源隔离潜力与“必须拆微服务”“具备独立发布需求”是不同论断，本实验没有证明后两者。

若需要决定架构，下一轮先定义业务SLO和真实到达率/热点比例，在相同总CPU、内存、连接池预算下比较单JVM限流与双JVM角色分工，延长预热和稳态时间、随机化场景顺序，并测真实后台履约链路。分片前还要验证跨分片分页、幂等唯一约束、事务和数据迁移成本。本次没有实施分片。

## 原始工件与复现

完整私有审计目录：`D:/shixi/smart-life/.superpowers/capacity-20261115/`（gitignored，保留）。Bench.java、run.py、spike_lib.py、fresh.py、control.py、http_phase.py均在其中；schema.sql/seed-*.sql/select.sql/plan-*.sql与计划txt、validation-*.txt、*-latencies.csv、*-stage.csv、*-errors.txt、*-capture.json、results.json、http-results.json、JVM日志/哈希、ownership ledger均保留。100万精确COUNT与每用户100行检查通过；information_schema.TABLE_ROWS为估算，不能替代COUNT。

本次命令（PowerShell；运行需要Docker/localhost访问权限）：

```powershell
Set-Location D:/shixi/smart-life
$capacityDocker = 'C:/Users/beemines/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
& $capacityDocker run -d --name sl-capacity-20261115-13416 --label capacity.owner=20261115 --cpus=2 --memory=1536m --memory-swap=1536m -p 127.0.0.1:13416:3306 -e MYSQL_ROOT_PASSWORD=CapacityOnly_20261115_fresh -e MYSQL_DATABASE=capacity_spike mysql:8.4 --innodb-buffer-pool-size=134217728 --max-connections=100 --performance-schema=ON
& D:/JDK/jdk21/bin/javac.exe .superpowers/capacity-20261115/Bench.java
python -X utf8 .superpowers/capacity-20261115/run.py
python -X utf8 .superpowers/capacity-20261115/control.py
python -X utf8 .superpowers/capacity-20261115/http_phase.py
python -X utf8 .superpowers/capacity-20261115/bind_check.py
python -X utf8 .superpowers/capacity-20261115/utc_fresh.py
python -X utf8 .superpowers/capacity-20261115/buffer_control.py
python -X utf8 .superpowers/capacity-20261115/make_report.py
```

以上是历史执行流程，不可对保留数据重复执行seed/固定批次ID。重建整轮须新唯一容器名、空数据卷、空schema、空输出目录及空闲端口，同时更新Bench.URL与脚本常量；不要覆盖本次证据、删除已有容器或复用生产schema。只读重查可启动本次owned容器，用相同JDBC classpath执行Bench sql及既有plan SQL，检查后停止owned容器。运行器的finally均仅停止其拥有的容器/Popen句柄；不删除数据卷。最终停止状态另存verification-final.json。

遇到的问题如实保留：默认sandbox Docker管道/本地网络被拒，改用已授权提升权限；登录PowerShell配置指向失效conda，改login:false；宿主CIM读取被拒；临时DDL提取/SQL分号处理误解析COMMENT，修复scratch提取而未改业务DDL；HTTP通过系统代理导致401，改direct-local opener；drain初版误统计SQL-only父任务，改HTTP任务范围；HTTP pilot每请求opener构造带来客户端开销，改持久连接并保留pilot；汇总JSON覆盖事故与完整重测见上文和ledger。失败尝试没有纳入成功场景。

解释指标时参考官方文档：[InnoDB buffer pool](https://dev.mysql.com/doc/refman/8.4/en/innodb-buffer-pool.html)、[buffer pool stats](https://dev.mysql.com/doc/refman/8.4/en/information-schema-innodb-buffer-pool-stats-table.html)、[InnoDB locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-information-schema-understanding-innodb-locking.html)、[Docker资源限制](https://docs.docker.com/engine/containers/resource_constraints/)、[docker stats](https://docs.docker.com/reference/cli/docker/container/stats/)。Innodb_buffer_pool_reads是物理读页计数，不能直接当磁盘忙碌百分比；CPU配额是实验控制变量，不代表生产已经如此配置。
