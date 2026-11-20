# 趣生活｜校园周边生活平台

在原趣票项目上扩展商铺发现、免费优惠券、签到打卡、评价点赞、个人中心和运营后台，保留校园抢票与模拟支付，并增加选店及客服助手。

- [产品 PRD](docs/product/校园周边生活平台-PRD.md) · [独立评审：94/100，通过](docs/product/校园周边生活平台-PRD评审.md)
- [启动、账号、实现与接口](docs/engineering/校园周边生活平台-运行与实现.md) · [本轮验证和截图](docs/verification/2026-11-19-local-life/README.md)
- Windows 已安装依赖时：项目根目录执行 `./backend/scripts/local-life.ps1 start`，打开 http://127.0.0.1:4386 。初次运行先按运行文档构建。
- 页面使用虚构商铺、样例评价及授权场景摄影；免费券核销和支付均为模拟操作。
- 默认关闭新模块；`LOCAL_LIFE_ENABLED=true` 开启趣生活，local-life 脚本自动设置。新模块按单库验收，不能与 CORE32 同时启用。
- 默认助手使用本地演示逻辑和真实业务查询；真实模型需另配密钥，本轮未声称已通过真实模型效果验收。

2026-11-20 升级：真实摄影、手机列表排版、持久化用户偏好、多轮选店、商家比较及客服规则/券包联合查询，见[使用说明](docs/engineering/真实照片与个性化生活助手.md)和[验证记录](docs/verification/2026-11-20-photos-assistant/README.md)。DeepSeek 本机配置入口：`./backend/scripts/configure-deepseek.ps1`，填写后重启自动启用。

![趣生活桌面首页](docs/verification/2026-11-20-photos-assistant/home-1440.png)

## 保留的校园票务能力

面向校园音乐节、话剧、赛事、讲座和社团活动，提供活动浏览、限时抢票、候补补位、模拟支付和超时关单。项目重点是热门活动缓存、限量票额秒杀及订单可靠性；活动浏览、候补和活动规划共用一个前端、一套账户和一个 Spring Boot 后端。

**这是后续唯一维护的项目。** `hm-dianping/backend-upgrade` 的已验收交易与缓存实现已合入这里。旧 EventFlow 的规划能力由本项目的 `planning` 模块承接；独立预约账户、`/api/v1` 接口和 `ef_*` 表不再作为另一套应用运行。旧目录仅供追溯，见 [合并说明](docs/项目合并说明.md)。

2026-10-29：单库与 CORE32 的异步建单、超时关单已统一改为 RocketMQ；可靠发送、消费幂等和数据库补偿继续保留。见[RocketMQ 改造说明](docs/engineering/RocketMQ迁移与验收.md)。

查阅代码说明和简历材料，请从[资料导航](docs/资料导航.md)进入。旧资料已经标注历史归属。

### 第一期智能票务助手

顶部“助手”入口支持按日期、预算、校区和类型找活动，多轮调整条件，查询最新余票、已发布规则及本人抢票结果。活动卡片进入原有购票详情；助手只查询和提供建议，不预占库存、不自动下单或支付。

默认 `ASSISTANT_MODE=stub`，页面明确显示“本地演示”，无需模型密钥。配置 `ASSISTANT_MODE=deepseek`、`DEEPSEEK_API_KEY`、`ASSISTANT_MODEL` 后使用真实模型的原生工具调用；本地演示及接口契约测试不等于真实模型效果评测。

本次 Windows 独立环境可运行 `./backend/scripts/assistant-local.ps1 start`，页面位于 `http://127.0.0.1:4286`；使用独立的 MySQL 34307、Redis 37380、RocketMQ 40876/40911 和后端 8193。具体启动、数据关联、接口与执行边界见[第一期助手说明](docs/engineering/第一期智能票务助手.md)，检查记录见[第一期验收](docs/verification/2026-11-09-assistant/README.md)。

本阶段后端交付见[收尾清单](docs/后端收尾清单.md)，五条亮点正文见[趣票后端简历定稿](docs/resume/趣票后端简历定稿.md)。按用户新文档新增的 **CORE32主交易模式**使用4个独立MySQL实例、每类8张表，主订单与请求、支付、任务按用户共置；库存保留源库裁决并通过持久占用和幂等补偿协调。学习和运行从[核心业务分库分表](docs/learning/09-核心业务分库分表-按参考文档学习.md)进入，验收见[本轮记录](docs/verification/2026-10-27-core32/README.md)。默认构建和下述通用启动仍为旧模式，启用新模式需要sharding构建与core32-local脚本。

## 项目内容

- 活动：按分类和校区浏览活动、场次及票档；活动基础信息使用 Caffeine → Redis → MySQL，多级缓存有过期、失效通知和有界回源。
- 抢票：Redis Lua 预扣票额，MySQL 请求与 Outbox 同事务，RocketMQ 异步建单，幂等消费；取消与到期释放票额，补偿可重试，恢复 epoch 阻止旧请求穿过库存重建。
- 用户：Cookie 会话、CSRF、订单查询、确认、取消、丢失响应后的原请求重试。
- 延迟关单与模拟支付：RocketMQ 延迟消息触发、MySQL 持久化任务兜底，CAS 状态更新和事务库存回补；模拟付款重复通知及关单后付款退款。通过 `SANDBOX_PAYMENTS=true` 开启后端模拟接口，详见[流程与 API](docs/learning/06-延迟关单与模拟支付.md)。不涉及真实资金。
- 历史订单：交易库保留库存与订单的本地事务，后台将订单投影到按用户路由的 32 张查询表；版本号抵御重复与乱序，定时核对负责缺失修复。
- 可选跨实例历史查询：通过 sharding 构建 profile 将读模型扩展为两个查询库、共 32 张表，支持独立同步、断点回填、核对和切换回退；默认仍查询本地读模型。见[实现与运行说明](docs/learning/07-订单查询分库分表.md)及[逐步验收](docs/verification/2026-10-27-sharding/README.md)。
- 可选CORE32主交易：按用户将权威订单及关联交易表拆成4库×8表，使用带租约的Snowflake、绑定表、展示配置广播及订单定位目录；源事务日志驱动迁移镜像，核对后维护切换，回退时反向复制。此模式下上述“库存与订单本地事务、历史投影查询”由跨库补偿和权威分片查询替代，两种模式不会同时处理同一交易。
- 候补：售罄后排队，释放票额后走同一套预占与建单规则。候补不承诺严格 FIFO，也不绕过一人一票档。
- 规划：Discovery、Planner、Review 生成校园活动安排，Java 校验时间、预算和余票。默认禁用，可用 stub 演示；建议不占票额，不代表抢票成功。
- 运维：健康检查、Prometheus、受管理员保护的库存不变量与流水线观测接口。

模拟支付不涉及真实资金或现场核销。项目按活动票档售票，每人每票档限购一次，不涉及选座；规划结果只提供建议，不提前锁票。

## 本地运行

需要 Java 17、Maven、Node.js 22+、Docker Compose。配置由脚本生成，不提交 `.env`。

```bash
bash run.sh init
bash run.sh up
bash run.sh build
bash run.sh dev
```

打开 `http://127.0.0.1:4176`。前端代理 `/v2` 到后端 `8093`；MySQL、Redis、RocketMQ NameServer/Broker 的本机端口分别为 `23307`、`26380`、`39876/39911`。本机已有中间件和依赖时，也可分别运行 `bash run.sh backend`、`bash run.sh frontend`。

`bash run.sh dev` 默认启用模拟支付，以便完整演示“抢票—支付—出票—超时关单—退款”流程，不涉及真实资金。需要关闭时可执行：

```bash
SANDBOX_PAYMENTS=false VITE_SANDBOX_PAYMENTS=false bash run.sh dev
```

前后端开关需要保持一致。开启后，待支付订单可进入模拟支付窗口，查看付款及退款状态，已关闭订单可通过“付款记录”重新查询。前端付款响应丢失时会查询同一笔付款，不创建第二笔付款通知。Vite 的开关属于构建配置，生产静态构建也需要显式设置 `VITE_SANDBOX_PAYMENTS=true`。

启用不调用外部模型的规划演示：`PLANNING_MODE=stub bash run.sh dev`。真实模型需要自行配置 `PLANNING_MODE=deepseek` 和 `DEEPSEEK_API_KEY`，本次合并验收没有调用付费模型。

容器部署：`bash run.sh init` 后运行 `docker compose up -d --build`，入口 `http://127.0.0.1:8080`。不要同时用本地后端和容器后端占用 8093。

本地观测平台使用可选 profile：

```bash
docker compose --profile observability up -d --build
```

Prometheus 默认位于 `http://127.0.0.1:29090`，Grafana 位于 `http://127.0.0.1:23000`。请在 `.env` 配置独立的 `METRICS_TOKEN` 和 `GRAFANA_ADMIN_PASSWORD`；Prometheus 使用只允许读取 `/actuator/prometheus` 的指标令牌，不能访问管理接口。

## 测试与迁移

V11 新增持久化关单任务、模拟支付流水和退款任务；现有待支付订单会补入关单任务。138 项测试及缓存数据复核属于 [2026-10-03 验收](docs/verification/2026-10-03-cache-payment-audit/README.md)。V12–V13 的分片同步与回填见[分片验收](docs/verification/2026-10-27-sharding/README.md)，后续真实会话、故障恢复和 HTTP 对照见[完整联调](docs/verification/2026-10-27-full-integration/README.md)；本机启动与复现见[运行说明](docs/learning/08-完整联调与复现.md)。

```bash
bash run.sh test    # 单元测试；外部中间件测试跳过
bash run.sh verify  # 完整后端测试，使用独立验收数据库和 Redis DB13
```

`verify` 需要先启动本项目中间件；数据库为 `life_choice_verification`，RocketMQ 建单 topic/group 为 `life-choice-verification-v1`。不清空业务库。浏览器验收见 [合并验收](docs/合并验收.md)。

数据库沿用原项目 V1–V6；V7 增加库存 epoch，V8–V10 增加订单读模型、Bloom 重建互斥和投影展示字段。不能把旧 `backend-upgrade` 的数据库直接接入本项目：它的 V5 与本项目 V5 内容不同。已有活动若包含订单而没有 epoch，需要管理员显式调用 `POST /v2/admin/activities/{id}/recover`；启动时不会自动重置该活动票额。

## 目录

```text
backend/         后端、数据库迁移、测试、实验脚本
frontend/        统一前端，根路径 /，只有一套登录
infra/           部署配置
compose.yaml     本项目中间件与前后端
run.sh           统一运行入口
docs/            当前说明；history/ 保存历史设计记录
evidence/        本次验收；history/ 保存合并前实验结果
```

本次代码验收与旧性能数据分开记录。历史缓存 2.20 倍、40 请求/秒持续 15 分钟等数据仍属于原测量版本，不能直接称作合并版的性能结果。

## 对照代码学习

缓存、查询分片、ID 选型、Kafka 可靠性及验证边界见 [五章学习说明](docs/learning/README.md)。
