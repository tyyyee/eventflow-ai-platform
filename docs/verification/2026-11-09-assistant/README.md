# 第一期智能票务助手验收

日期：2026-11-09。实施目录：`F:\00简历整体修改\04-魔改点评\加了agent之后的内容`。本次未修改原后端项目目录。

本文记录第一期及五轮真实模型冒烟。后续40个可执行场景、20个真实模型修前修后对照及8个新增场景验收，见[Agent效果评测](../2026-11-09-assistant-evaluation/README.md)；下表原有批次保留原始数量，不与后续重复计数。

## 交付范围

| 需求 | 已实现及验收方式 |
| --- | --- |
| 带条件找活动 | 日期、时段、预算、校区、类型；比较返回结果与实际活动及售票数据 |
| 多轮调整 | 修改校区保留时间、预算和类别；支持清空预算及寻找更便宜的活动 |
| 余票与序号 | “第几个”指向最近展示的列表；重新读取交易价格与数据库余票，不读取规划影子库存 |
| 售罄后的替代 | 先查余票，售罄时按原约束找替代，不自动放宽预算、校区或类型 |
| 原购票入口 | 活动卡片定位原详情中的对应购买入口，由用户自行提交抢票 |
| 规则咨询 | 返回共同规则、具体活动条款和入场须知；不推测未发布退改规则 |
| 本人订单 | 读取原 RocketMQ 消费者实际创建的订单；不将已受理误报为建单成功，不触发查询中的超时关单 |
| 轻量 Harness | 身份、工具白名单、参数、预算、限流、持久化任务与步骤、租约、恢复、轨迹及事实化输出 |
| 对话恢复 | 刷新恢复会话；丢失发送响应后使用同一请求 key；新对话不带入旧条件 |

所有新增业务工具均为只读。没有新增自动抢票、支付、取消订单、自动退款或后台故障修复功能。

## 检查结果

| 检查批次 | 结果 | 说明 |
| --- | --- | --- |
| 后端全量回归与 sharding 构建 | 219项中135通过，84项按环境条件跳过；构建通过 | 包含助手测试，不将跳过项目计为通过 |
| 最终助手针对性测试 | 28项通过，0失败 | 多轮条件、序号、影子库存隔离、来源时间、价格变化、本人订单、禁止越权、原请求重试、租约、中断恢复、模型协议等 |
| 真实中间件 HTTP 联调 | 2项通过，0失败 | 新 MySQL 完成 V1—V17；实际 Redis/RocketMQ；真实异步建单后由助手查询 |
| 助手浏览器流程 | 9组通过 | [机器结果](browser/results.json)；使用真实本机后端及数据库，模型为本地演示 |
| 原活动广场浏览器回归 | 6组通过 | [机器结果](discovery-regression/results.json)；覆盖收藏、跨标签同步、筛选、详情、布局与存储异常 |
| 前端生产构建 | 通过 | Vite build，包含新增助手页面与五项手机导航 |
| 补充真实模型冒烟 | 同一会话内5轮通过，10次模型调用，21507 tokens | [原始记录](live-smoke.json)；覆盖活动搜索、校区修改、序号查余票、规则、本人无订单查询，不能作为大样本效果统计 |

两项助手中间件测试在全量批次中按条件跳过，在独立联调批次单独启用。各批次有包含关系，不能简单相加当成互不重复的测试数量。

本机日志保存在 `.local/assistant/test-all.log`、`test-focused.log`、`test-integration.log`；JUnit 明细在 `backend/target/surefire-reports`。源码入口：`AssistantBusinessTest`、`AssistantHttpTest`、`DeepSeekAssistantModelTest`、`AssistantIntegrationTest`；浏览器入口：`frontend/tests/assistant-flow.mjs`。结构化验收摘要见 [validation.json](validation.json)。

浏览器场景包括：匿名登录要求、第一条消息创建会话、校区修改后条件保留、序号定位并进入实际购票、读取实际订单、已发布规则与刷新恢复、发送响应丢失后的幂等重试、四种屏幕宽度、新对话清空偏好。

## 截图

- [桌面活动推荐](browser/assistant-activities-desktop.png)
- [手机活动推荐](browser/assistant-activities-mobile.png)
- [1440宽度](browser/assistant-1440.png)、[768宽度](browser/assistant-768.png)、[390宽度](browser/assistant-390.png)、[320宽度](browser/assistant-320.png)

已目视检查推荐卡片和手机布局。验收过程中发现第五项导航在手机上换行，已调整成五列并加入导航同一行的断言。查询来源的时间保留实际读取时间，任务恢复不会把旧快照重新标记成刚刚查询。

## 环境及使用

Windows 独立环境：前端 `http://127.0.0.1:4286`，后端8193；MySQL34307、Redis37380、RocketMQ NameServer40876/Broker40911。运行库 `qupiao_assistant`，联调库 `qupiao_assistant_verify`；联调使用 Redis DB13 和单独消息主题，不清空运行库或其他项目的数据。

```powershell
# 在本项目根目录
./backend/scripts/assistant-local.ps1 status
./backend/scripts/assistant-local.ps1 start
./backend/scripts/assistant-local.ps1 verify
./backend/scripts/assistant-local.ps1 stop
```

详细配置、工具、数据来源、恢复机制和接口见[实现说明](../../engineering/第一期智能票务助手.md)。

## 验收边界

1. 业务数据为校园活动演示数据；数据库、Redis、消息队列与 HTTP 请求均真实运行。本次不是吞吐量或大数据量性能实验，不新增 QPS、延迟或模型成功率数字。
2. 默认配置 `ASSISTANT_MODE=stub`，是明确标识的确定性示例解析器；上述浏览器和后端确定性测试仍使用stub或协议夹具。补充的真实模型冒烟单独使用 `deepseek`，其21507 tokens来自真实响应，不能与协议测试中的模拟用量混用。
3. 已补充一段五轮真实模型核心对话；没有完成系统性真实模型评测，不报告总体理解准确率或端到端成功率。按当时较高的Flash输出单价计算，已报告tokens的保守费用估算为0.172056元；余额接口前后都显示9.94元，不能当成实际账单或零费用证明。扩展评测设计见[面试学习稿](../../interview/07-Agent与Harness-面试学习稿.md)。
4. 本轮真实交易联调使用单库模式；CORE32 助手查询适配已参与 sharding 编译，但未重新运行四个 MySQL 分片的助手端到端验收。
5. Windows 本机环境已验收；Compose 已接入助手配置变量，本轮未重新构建和验收容器部署。
6. 只读步骤可根据持久化检查点恢复；若进程中断时模型调用结果或用量未知，会失败收口，不能描述成所有任务都能无条件续跑成功。
