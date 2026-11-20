# RocketMQ 异步建单与超时关单

单库与 CORE32 模式统一使用 RocketMQ 5.3.2。请求和建单 Outbox 在同一个数据库事务内保存，订单和关单任务也在同一个事务内保存。消息负责触发处理，数据库负责记录业务结果；支付、关单、库存回补与退款继续使用原来的条件更新和幂等任务。

## 异步建单流程

1. Redis Lua 校验资格并预扣库存。
2. 在用户对应的数据库中，同事务保存请求与 Outbox，接口返回“已受理”。
3. 后台领取 Outbox，发送普通 RocketMQ 消息。只有收到 `SEND_OK` 才标记已发送；网络异常、落盘超时或发送结果不确定均保留重试资格。
4. 消费者校验消息版本、请求与业务键，在数据库事务内处理库存和订单。事务成功后，监听器才返回消费成功。
5. 发生数据库异常或提交后确认丢失，RocketMQ 重投消息。请求锁、业务状态和唯一索引防止重复建单。Redis 补偿另行执行，不因 Redis 故障阻塞已经提交的订单确认。

`OrderMessages` 隔离业务处理与客户端协议，`RocketMessageBus` 使用官方 remoting 客户端。消费失败设置延迟等级 3，即约 10 秒后重试；最大重试次数配置为 `Integer.MAX_VALUE`。这不代替积压监控、消息保留策略和业务超时补偿。非法消息必须先持久化到 `ux_mq_poison` 再返回成功，隔离表不可写时继续重试。

开发环境启动前会准备缺失的 Topic，已有 Topic 不修改队列数；网络故障不会被误判为 Topic 缺失。独立部署时可设置 `ROCKETMQ_CREATE_TOPICS=false`，由部署流程事先创建 Topic。消费者重试计数通过 `life.mq.consumer.retries` 暴露给监控。

消息的 `keys` 用于关联和校验，不承诺同一用户的严格顺序消费；交易正确性由数据库事务、锁和幂等约束保证。

## 延迟关单流程

订单创建时保存支付截止时间和持久化关单任务。发送器读取尚未发送的任务，调用 `setDeliverTimeMs` 设置绝对投递时间。已经超过截止时间的任务直接发送普通即时消息。

发送成功后写入 `message_sent_at`，以后不再周期性重发这条任务。发送失败则推迟 5 秒重试。发送成功后、标记前崩溃可能造成重复消息，消费者必须幂等处理。

消费者收到消息后重新读取订单状态和截止时间：未到期则失败重试；已支付或已关闭则结束；仍待支付且已到期才关闭。支付与关单通过同一请求锁和 CAS 条件更新协调，只有成功改变状态的事务才创建库存回补任务。关闭后收到付款通知则记录退款任务，模拟渠道退款账本保证重复执行不会重复退款。

日常到期由 RocketMQ 触发；补偿扫描默认每 60 秒执行一次，只查询已经到期且未完成的持久化任务。积压时分批处理，因此不承诺所有订单都在截止时刻立即关闭。

## 数据库改动与切换

- 源库 Flyway `V16__rocketmq_delivery.sql` 为 `ux_close_task` 增加 `message_sent_at` 与发送索引，新增 `ux_mq_poison`。旧 Kafka 中仍处于 ACCEPTED 的请求，其 Outbox 会重新置为待发送。
- CORE32 的 `CoreStore` 为已有 32 张 `cx_close` 表补充同一字段与索引，支持中断后重启补建索引。新增字段不进入交易数据的业务一致性校验。
- CORE32 处理器启动时，将仍为 ACCEPTED 的请求对应 Outbox 重新置为待发送。已成功或已终结的请求不重置。正向迁移和反向回填同时清除未完成关单任务的发送标记，避免沿用另一套处理器的旧投递记录。
- 旧关单任务的 `message_sent_at` 默认为空，会自动补发延迟消息。旧 Redis 关单 ZSet 不再参与调度，保留它不会影响新链路。

切换时先停止旧后端及消费者，等待在途事务结束，再启动 RocketMQ 和新后端。旧 Kafka 消息不会自动搬运到 RocketMQ，恢复依据是数据库请求、Outbox 和关单任务。不要让新旧交易处理器同时处理同一业务库。

本轮包含数据库结构升级和消息配置切换，不能只换回旧 jar 就声称已经完成回退。回到旧消息方案，需要停写并根据请求状态恢复旧 Outbox，重新安排旧关单任务，再核对数据库结果。

## Windows 隔离运行

Java 使用 17。将 [Apache 官方发行包](https://archive.apache.org/dist/rocketmq/5.3.2/) 下载到 `.local/rocketmq`，按官方 SHA512 校验后解压到 `.local/rocketmq/distribution/rocketmq-all-5.3.2-bin-release`。发行包和消息数据不提交到仓库。

```powershell
./backend/scripts/rocketmq-local.ps1 start
./backend/scripts/rocketmq-local.ps1 status
```

NameServer 为 `127.0.0.1:39876`，Broker 为 `127.0.0.1:39911`。本地脚本开启时间轮与同步刷盘，消息保存在与本项目路径绑定的临时运行目录；这是单 Broker 开发环境，不提供主从容灾。

已有 CORE32 演示继续使用 `core32-local.ps1`，构建时启用 `-Psharding`；先确保源库、四个主库和 Redis 已启动。完整回归使用独立数据库和 Redis DB13，CORE32 专项使用 `qupiao_core_verification`、四个 `qupiao_core32_verification` 库和 Redis DB12。

```powershell
./backend/scripts/integration-tests.ps1
$env:ROCKETMQ_INTEGRATION='true'
./backend/scripts/core32-local.ps1 test
```

停止 RocketMQ 使用 `rocketmq-local.ps1 stop`。原 `integration-local.ps1 start-kafka/stop-kafka` 仅保留给历史实验；默认启动已改为 RocketMQ。

## Docker 与本机后端

完整容器部署使用 `compose.yaml`，后端连接 `rocketmq-namesrv:9876`，Broker 广告地址为容器内名称。RocketMQ 端口不映射到宿主机。

本机 JVM 配合容器中间件时，使用 `compose.host.yaml` 叠加配置；`bash run.sh up` 已按这个方式启动。此时 Broker 广告地址为 `127.0.0.1:39911`，本机后端设置 `ROCKETMQ_NAMESERVER=127.0.0.1:39876`。不要用该叠加配置运行容器中的后端，因为容器内的回环地址含义不同。

两个配置都开启时间轮、同步刷盘和开发用的自动建 Topic；验收结果以本地实际运行记录为准。真实部署还需独立规划认证、Topic 管理、消息保留与 Broker 容灾。

## 验证与简历

结果见 [RocketMQ 验收记录](../verification/2026-10-29-rocketmq/README.md)。自然到期关单测试不调用数据库补偿扫描，单独证明 RocketMQ 延迟消息参与了关单。

异步返回的是“已受理”，实际创建完成时间由后续处理决定。当前没有重新测出吞吐提升或“响应速度提升 70%”，简历不添加这类数字。

代码入口：`Delivery`、`DelayedClose`、`RocketMessageBus`、`core32.CoreJobs`。关于客户端时间戳接口与配置，参见 [官方 Message 源码](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/common/src/main/java/org/apache/rocketmq/common/message/Message.java) 和 [官方存储配置](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/store/src/main/java/org/apache/rocketmq/store/config/MessageStoreConfig.java)。
