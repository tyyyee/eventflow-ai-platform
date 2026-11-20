# RocketMQ 建单与关单验收

本轮将单库与 CORE32 的异步建单迁移至 RocketMQ，并用延迟消息替换 Redis 关单 ZSet 的日常触发。数据库 Outbox、请求幂等、支付与关单的条件更新、库存补偿和退款任务继续保留。测试使用模拟账户、活动与支付通知，没有真实资金操作。

## 测试结果

| 验证范围 | 结果 | 证据 |
| --- | --- | --- |
| sharding 构建下的完整回归与最终打包 | 190 项，160 通过、30 条件跳过，0 失败、0 错误 | [完整回归摘要](regression-summary.json) |
| 四库专项 | 18 项全部通过，0 跳过、0 失败、0 错误 | [四库摘要](core-summary.json) |
| 真实登录与 CORE32 HTTP 故障联调 | 11 组全部通过；240 次查询采样无错误、无结果差异 | [HTTP 联调结果](http-result.json) |
| Docker Compose 配置 | 完整容器配置与本机 JVM 叠加配置均通过 `config --quiet` | 项目 `compose.yaml` 与 `compose.host.yaml` |
| 运行脚本 | PowerShell 解析与 Node 脚本语法检查通过 | 项目 `backend/scripts` |

四库专项与完整回归存在重复用例，数量不能直接相加。完整回归跳过的主要是独立四库测试、历史分片环境和容量压测；四库行为由单独启用的专项覆盖。

最终运行包与关键源码的 SHA256 见 [构建清单](build-manifest.json)。

## HTTP 故障恢复

通过真实 Cookie 会话注册 24 个模拟账户，在四个数据库创建主订单，并验证所有权隔离、重复付款通知和候补补位。随后在“源库库存占用已提交、分片订单未提交”和“付款渠道已登记、本地支付未完成”两个位置实际终止 JVM，重启后均恢复业务结果。

实际停止并重启 RocketMQ 后，已受理请求由数据库 Outbox 重试完成。Redis 中断和一个主库中断也完成恢复验证。订单按原本五分钟截止时间自然关闭，迟到付款仅退款一次。

最终 `accepted`、`outbox`、`actions`、`overdue`、`refunds` 均为 0，库存与状态不变量均为 0，错片数为 0。HTTP 采样属于少量模拟订单的功能检查，没有作为吞吐或性能提升结论。

联调过程中将定期关单补偿间隔临时调整为一小时，完成后已恢复默认一分钟间隔，并以不启用故障注入的普通配置重启 38193 后端。

## 已验证的交易行为

- Outbox 发送失败保留任务并重试，只有 `SEND_OK` 才允许标记已发送。落盘超时等非成功状态仍视为发送不确定。
- 真实 RocketMQ 消费前发生异常后重投；订单提交后确认丢失再次投递，数据库仍只有一张订单。
- 关单消息发送成功、标记前中断后可以重发；重复消息只产生一次库存回补任务。
- `RocketOrderFlowTest` 使用真实 Broker 和隔离 H2，缩短支付截止时间后，通过时间戳延迟消息自然关单，不调用补偿扫描；另行验证已支付订单保护、迟到付款与退款幂等。
- CORE32 使用真实四个 MySQL 实例和 Redis，实际通过 RocketMQ 在四个库创建订单，并验证延迟关单、发送标记和库存恢复。
- 正向迁移、版本核对、切换及反向回填仍通过，回退后的未完成关单任务重新进入待发送状态。
- 非法消息先落隔离表；隔离表不可写时不返回消费成功。不同 Broker 的队列位置不会混用旧 Kafka 的唯一索引。
- HTTP 用例覆盖真实注册、登录、权限、重复请求、模拟支付、超时确认及候补。

`RocketMessageBusTest` 另外验证开发环境准备 Topic 时不会改动已有 Topic，网络错误不会被当作 Topic 缺失。

## 运行环境与复现

Windows、Java 17、MySQL 8.0.31、Redis 3.2.100、Apache RocketMQ 5.3.2。RocketMQ 发行包按 Apache 官方 SHA512 校验，NameServer 使用 39876，Broker 使用 39911，时间轮开启、同步刷盘。该环境只有一个 Broker，不代表主从高可用。

完整回归脚本创建独立 `qupiao_verify_时间戳` 数据库，Redis 使用 DB13，消息 Topic 和消费组单独命名。四库专项使用 `qupiao_core_verification` 和四个 `qupiao_core32_verification` 库，Redis 使用 DB12。主演示业务库未被清空。

```powershell
./backend/scripts/rocketmq-local.ps1 start
./backend/scripts/integration-tests.ps1 -Package
node backend/scripts/rocketmq-report.mjs regression
$env:ROCKETMQ_INTEGRATION='true'
./backend/scripts/core32-local.ps1 test
node backend/scripts/rocketmq-report.mjs core
```

四库专项使用了运行期间线程续租的现有 Snowflake 实现，包含租约等待用例，因此执行时间会超过普通单元测试。

## 验收中修正的问题

新建 Topic 的首次注册曾超过 HTTP 用例的 15 秒等待时间。开发启动增加缺失 Topic 的预创建与路由可见性检查，已有 Topic 不调整队列；修正后 HTTP 10 项及完整回归通过。消费失败的重试间隔也改为明确的延迟等级 3。

新增四库测试最初在逻辑 SQL 中使用 `TIMESTAMPADD(SECOND,...)`，ShardingSphere 将 `SECOND` 解析为列名。测试改为传入时间戳参数后，18 项专项全部通过。

## 简历使用范围

可说明已实现 RocketMQ 异步建单、延迟关单、可靠发送、消费幂等和异常补偿。这里验证的是功能与恢复行为，没有形成前后对照的吞吐或延迟收益，不使用“响应提升 70%”之类性能数字。历史缓存实验仍按其原日期与实验条件解释。

流程、数据库变更与部署配置见 [RocketMQ 改造说明](../../engineering/RocketMQ迁移与验收.md)。
