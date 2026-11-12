# 企微客户营销自动化系统 SPEC

> 版本：v1.1  
> 定位：单人 Java 开发者的私域客户运营系统  
> 技术栈：Spring Boot 4.1 + Java 17 + MySQL 8 + Spring JDBC（JdbcClient）+ 手写企微 REST 网关  
> 变更记录：v1.1 按实际工程调整——ORM 由 MyBatis-Plus 改为 JdbcClient（适配 Boot 4），access_token 改为进程内缓存（单实例部署），暂不引入 Redis 与 WxJava

---

## 1. 文档说明

### 1.1 目标读者

开发者本人。本文件是**唯一实现依据**，任何与代码冲突的地方以本文件为准，修改需先改本文件。

### 1.2 范围

**包含**：需求边界、业务模型、模块划分、数据模型、企微接口对接、核心接口契约、交付分期。

**不包含**：UI 视觉稿、部署运维细节、广告投放、会话存档合规方案。

### 1.3 前提假设

| 编号 | 假设 | 影响 |
|---|---|---|
| A1 | 已用手机号注册企微企业，**未验证主体**（无营业执照） | 可开发联调；外部联系人上限低、部分接口受限 |
| A2 | 团队只有 1 人，既是管理员、唯一销售，也是测试期唯一客户 | 不做角色权限，不做任务分配 |
| A3 | 客户量级 ≤ 5 万 | 单机 MySQL + 轮询足够，不需要分库分表 |
| A4 | 不自建消息队列 | 用数据库任务表承担异步 |
| A5 | 不使用会话存档能力 | 合规成本高，非必须 |
| A6 | 开发期没有真实客户来源 | 不做渠道二维码与来源标签，先跑通管道 |
| A7 | 暂不需要模板管理 | 欢迎语与话术走配置文件，不做素材库 |

---

## 2. 产品定位与核心判断

### 2.1 一句话定位

**系统负责"识别时机、准备素材、派发任务"，人负责"按下发送"。**

### 2.2 为什么是半自动

企微开放接口有三条硬约束，直接决定了产品形态：

| 约束 | 接口事实 | 系统能做 | 系统不能做 |
|---|---|---|---|
| 不能主动单聊 | 除欢迎语外，无"主动发消息给客户"接口 | 提醒销售、准备话术 | 自动私聊客户 |
| 群发需人工确认 | `add_msg_template` 是把消息推给**员工**，员工点确认才真正发出 | 创建群发任务、追踪结果 | 自动群发 |
| 群发频率极严 | 每客户每天最多 1 条，每成员每天 1 次群发机会 | 配额控制、错峰调度 | 密集序列触达 |

### 2.3 因此系统的核心资产是

1. **客户档案**（谁、从哪来、什么标签、跟谁）
2. **时机识别**（谁该跟、什么时候跟）
3. **任务派发**（把"该做什么"变成待办，而不是发出去的消息）

> 判断标准：如果一个功能不能提升"客户档案质量"或"跟进时机准确度"，就不做。

---

## 3. 业务对象与角色

### 3.1 角色

| 角色 | 说明 | 系统权限 |
|---|---|---|
| 管理员 | 本系统使用者，即开发者本人 | 全部配置 |
| 销售成员 | 企微成员，接收跟进任务 | 只读任务列表 |
| 客户 | 外部联系人，只感知到企微本身 | 无系统账号 |

### 3.2 核心对象

| 对象 | 定义 | 关键属性 |
|---|---|---|
| **客户** Customer | 一个外部联系人 | external_userid、归属成员、添加方式、状态 |
| **员工** Member | 一个企微成员 | userid、姓名、部门 |
| **事件** Event | 客户或员工发生的一件事 | 类型、发生时间、来源、载荷 |
| **规则** Rule | "什么事件 + 什么条件 → 做什么" | 触发事件、条件表达式、动作链 |
| **流程** Journey | 一组按时间排布的动作 | 节点数组、版本 |
| **任务** Task | 待执行或待确认的一件事 | 类型、到期时间、状态、是否需确认 |
| **触达记录** Message | 一次消息投递的结果 | 模板、企微 msgid、发出时间、状态 |

### 3.3 关键术语统一

- **归属成员**：客户的 `follow_user`，一个客户可有多个成员，本系统只取第一个。
- **渠道来源**：通过带参数二维码（`state`/`scene`）区分，取值如 `douyin`、`offline`、`referral`。
- **静默期**：同一客户在 N 小时内不重复生成同类任务，默认 48 小时。

---

## 4. 核心业务流程

### 4.1 流程一：新客户接入（快路径）

```
客户扫码加好友
   → 企微回调 change_external_contact(add)
   → 系统落客户档案 + 来源渠道
   → 立即发送欢迎语（20 秒窗口内，硬限制）
   → 异步打标（来源渠道标签）
   → 异步生成"首次跟进"任务（24 小时内提醒）
```

**关键点**：欢迎语必须在添加好友后 20 秒内发出，超时接口直接失败。所以这条路径**不能走轮询**，必须在回调线程内同步完成，且失败要降级记录而不是重试。

### 4.2 流程二：定时动作（慢路径）

```
规则命中 → 展开为 N 条任务写入 mk_task（带 due_at）
   → 轮询执行器每 5 秒取到期任务
   → 按动作类型执行：
       tag      → 调打标接口（自动）
       remind   → 写站内待办（自动）
       groupSend→ 创建群发任务，标记"待人工确认"
   → 回写状态
```

### 4.3 流程三：客户流失

```
企微回调 del_external_contact
   → 客户状态置 deleted
   → 写入静默名单（不再生成任务）
   → 生成"流失复盘"任务
```

---

## 5. 功能需求

### 5.1 P0 — 接入与客户资产

| 编号 | 功能 | 行为定义 | 状态变化 |
|---|---|---|---|
| F-01 | 企微回调接入 | 校验签名、AES 解密、幂等去重后落库 | — |
| F-02 | 客户档案同步 | 回调新增/删除客户时同步；每日全量对账一次 | `active` ↔ `deleted` |
| F-03 | 员工与标签同步 | 启动时 + 每日同步通讯录与标签库 | — |
| F-04 | 欢迎语 | 添加好友 20 秒内发送；内容取渠道对应模板 | 消息 `sent` / `failed` |
| F-05 | 自动打标 | 按来源渠道自动打标，同名标签不重复创建 | 客户标签变更 |
| F-06 | 客户列表 | 支持按标签、来源、状态筛选；支持关键词搜索 | — |

**异常规则**

- 回调重复投递：以 `event_id` 唯一键去重，重复直接返回成功。
- 欢迎语发送失败：记录失败原因，**不重试**（超时重试必然失败），转人工提醒。
- 客户已存在：更新资料，不重复发欢迎语。

### 5.2 P1 — 规则与提醒

| 编号 | 功能 | 行为定义 |
|---|---|---|
| F-07 | 规则配置 | 配置触发事件 + 条件 + 动作链，支持启用/停用 |
| F-08 | 条件求值 | 支持标签包含、来源等于、添加天数大于、最近 N 天无消息 |
| F-09 | 定时任务生成 | 按节点延迟展开为任务，落库带 `due_at` |
| F-10 | 跟进提醒 | 到达时间点生成待办，站内展示 |
| F-11 | 静默期控制 | 同一客户同类任务 48 小时内不重复生成 |
| F-12 | 数据看板 | 新增客户数、各渠道来源占比、待跟进任务数、任务完成率 |

### 5.3 P2 — 群发任务

| 编号 | 功能 | 行为定义 |
|---|---|---|
| F-13 | 人群圈选 | 按标签/来源/状态组合圈人，预估人数 |
| F-14 | 创建群发任务 | 调企微接口创建群发，状态置 `pending_confirm` |
| F-15 | 配额控制 | 同客户同一天只允许 1 条群发，超限任务置 `skipped` |
| F-16 | 结果回查 | 定时拉取群发发送结果，更新为 `sent` |

### 5.4 明确不做

- 自动私聊客户（接口不支持，任何绕过方案均违规）
- 拖拽式流程画布（JSON 配置足够）
- 多租户、多角色权限
- 会话内容存档与质检
- 客户朋友圈自动发布（需企业认证 + 人工确认，收益低）

---

## 6. 系统架构

### 6.1 模块划分

```
marketing/
├── wecom-gateway/     企微 API 封装：token 管理、加解密、限流、重试
├── contact/           客户、员工、标签的同步与查询
├── event-ingest/      事件归一化、幂等、路由
├── rule-engine/       规则匹配、条件表达式求值
├── journey/           流程定义、节点展开为任务
├── task/              任务落库、轮询调度、配额、重试
├── action/            动作执行器（welcome/tag/remind/groupSend）
├── console/           配置后台与看板
└── common/            统一响应、异常、审计
```

### 6.2 分层约束

- Controller 只做参数校验与响应组装，不含业务判断。
- `action` 层不直接调企微 HTTP，必须经 `wecom-gateway`。
- 所有外部调用必须带超时（连接 3s / 读取 5s）与明确失败语义。

### 6.3 企微 Gateway 关键设计

```java
public interface WecomApi {
    String getAccessToken();                       // 缓存 2h，刷新加分布式锁
    void sendWelcome(String userId, WelcomeMsg msg);   // 20 秒窗口，超时失败
    void markTag(String userId, String extId, String... tags);
    String createGroupMsg(GroupMsg msg);           // 返回 msgid，需员工确认
    GroupMsgResult getGroupMsgResult(String msgid);
    CustomerDetail getCustomer(String extId);
}
```

- `access_token` 存 Redis，key 为 `wecom:token:{corpId}:{agentId}`，过期前 5 分钟主动刷新。
- 刷新使用 Redis 分布式锁，避免并发刷新导致互相失效。
- 所有接口统一包装：失败时区分"HTTP 失败"与"业务错误码失败"。

### 6.4 开发期环境要求

企微回调必须是公网可访问的 HTTPS 地址，本地开发需要内网穿透。

| 项 | 要求 | 说明 |
|---|---|---|
| 回调 URL | HTTPS、3 秒内响应 | 本地用 cpolar / natapp / frp 映射 |
| 可信域名 | 在企微后台完成所有权校验 | 用穿透域名即可 |
| 企业可信 IP | 配置调用方出口 IP | 未配置时调用接口报 60020 |
| 自测客户 | 自有微信加企微成员 | 可触发真实 add_external_contact 回调 |

---

## 7. 数据模型

### 7.1 表清单

| 表 | 用途 | 量级预估 |
|---|---|---|
| `ct_customer` | 客户档案 | 5 万 |
| `ct_member` | 员工 | 个位数 |
| `ct_tag` | 标签定义 | 百级 |
| `ct_customer_tag` | 客户-标签关联 | 20 万 |
| `ev_event` | 事件流水 | 百万级（按月归档） |
| `mk_rule` | 规则定义 | 百级 |
| `mk_journey` | 流程定义 | 百级 |
| `mk_task` | 任务表 | 百万级（按月归档） |
| `mk_message` | 触达记录 | 百万级 |
| `mk_suppression` | 静默名单 | 万级 |
| `mk_audit_log` | 操作审计 | 十万级 |

### 7.2 核心表结构

```sql
-- 客户档案
CREATE TABLE ct_customer (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  external_uid VARCHAR(64)  NOT NULL COMMENT '企微 external_userid',
  name         VARCHAR(64)  NOT NULL DEFAULT '',
  avatar       VARCHAR(255) NOT NULL DEFAULT '',
  follow_user  VARCHAR(64)  NOT NULL COMMENT '归属成员 userid',
  add_way      INT          NOT NULL DEFAULT 0 COMMENT '企微添加方式枚举',
  source       VARCHAR(32)  NOT NULL DEFAULT 'unknown' COMMENT '渠道来源',
  status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active/deleted',
  add_time     DATETIME     NOT NULL,
  last_active  DATETIME     NULL,
  UNIQUE KEY uk_ext (external_uid),
  KEY idx_source (source),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 任务表（系统心脏）
CREATE TABLE mk_task (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  customer_id BIGINT      NOT NULL,
  journey_id  BIGINT      NULL,
  node_index  INT         NOT NULL DEFAULT 0,
  action_type VARCHAR(16) NOT NULL COMMENT 'welcome/tag/remind/groupSend',
  payload     JSON        NULL,
  due_at      DATETIME    NOT NULL,
  status      VARCHAR(20) NOT NULL DEFAULT 'pending'
              COMMENT 'pending/running/done/failed/skipped/canceled/pending_confirm',
  need_confirm TINYINT    NOT NULL DEFAULT 0,
  biz_date    DATE        NOT NULL COMMENT '用于配额唯一约束',
  retry       INT         NOT NULL DEFAULT 0,
  last_error  VARCHAR(500) NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_due (status, due_at),
  KEY idx_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 7.3 配额控制（重要）

群发配额**必须用数据库唯一索引兜底**，不能只靠代码判断：

```sql
-- 同一客户同一天只能有一条群发任务（canceled/skipped 不占用配额）
CREATE UNIQUE INDEX uk_group_quota
  ON mk_task (customer_id, action_type, biz_date);
```

> 实现说明：`skipped` 与 `canceled` 任务需在插入前删除或走独立表，否则仍占用唯一键。推荐做法：配额表独立为 `mk_quota(customer_id, action_type, biz_date)`，用 `INSERT ... ON DUPLICATE KEY UPDATE` 判断是否超限。

### 7.4 幂等设计

| 场景 | 幂等手段 |
|---|---|
| 企微回调重复 | `ev_event` 上 `event_id` 唯一键 |
| 任务重复消费 | `UPDATE mk_task SET status='running' WHERE id=? AND status='pending'`，影响行数为 0 则跳过 |
| 欢迎语重复发送 | 客户表记录 `welcomed_at`，非空则跳过 |

---

## 8. 企微接口对接清单

### 8.1 接收（回调）

| 回调类型 | 事件 | 系统动作 |
|---|---|---|
| `change_external_contact` | add_external_contact | 建档案 + 欢迎语 + 打标 |
| `change_external_contact` | del_external_contact | 置 deleted + 静默 |
| `change_external_tag` | 标签变更 | 更新关联 |
| 消息回调 | 客户发消息 | 更新 `last_active`，可触发规则 |

**回调必须实现**：签名校验、AES 解密、5 秒内返回、幂等。

### 8.2 调用（主动）

| 用途 | 接口 | 频率注意 |
|---|---|---|
| 获取 token | `/cgi-bin/gettoken` | 缓存 2h |
| 客户详情 | `externalcontact/get` | 按需 |
| 客户列表 | `externalcontact/list` | 每日全量对账 |
| 成员客户列表 | `externalcontact/get_follow_user_list` | 每日 |
| 打标签 | `externalcontact/mark_tag` | 按需 |
| 欢迎语 | `externalcontact/send_welcome_msg` | **20 秒窗口** |
| 创建群发 | `externalcontact/add_msg_template` | **需员工确认** |
| 群发结果 | `externalcontact/get_groupmsg_send_result` | 定时拉取 |
| 通讯录 | `user/list`、`tag/list` | 每日 |

> 具体频率限制以企微官方最新文档为准。所有调用统一走 gateway 的限流器，默认按 20 QPS 保守配置。

---

## 9. 状态机

### 9.1 任务状态

```
pending ──(执行器抢占)──> running ──成功──> done
   │                        │
   │                        ├─失败(可重试)──> pending（retry+1，退避）
   │                        └─失败(超限)────> failed
   ├─(配额超限)──> skipped
   ├─(客户流失/退订)──> canceled
   └─(需人工确认)──> pending_confirm ──确认──> done
                                     └─拒绝/过期──> canceled
```

### 9.2 任务重试策略

| 动作类型 | 是否重试 | 策略 |
|---|---|---|
| welcome | ❌ | 20 秒窗口过期即失败，重试无意义 |
| tag | ✅ | 最多 3 次，间隔 1/5/15 分钟 |
| remind | ✅ | 最多 3 次 |
| groupSend | ❌ | 业务失败直接 failed，转人工 |

---

## 10. 核心接口（系统自身）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/wecom/callback` | 企微回调入口（签名校验 + 解密） |
| GET | `/api/customers` | 客户列表（分页 + 筛选） |
| GET | `/api/customers/{id}` | 客户详情 + 事件时间线 |
| POST | `/api/tasks/{id}/confirm` | 确认群发任务 |
| POST | `/api/tasks/{id}/cancel` | 取消任务 |
| GET | `/api/rules` / POST | 规则查询与保存 |
| POST | `/api/journeys` | 创建流程 |
| GET | `/api/dashboard/overview` | 看板数据 |
| POST | `/api/sync/full` | 手动触发全量同步 |

**统一响应**

```json
{ "code": 0, "message": "ok", "data": {} }
```

业务错误码分段：`1xxx` 参数类、`2xxx` 权限类、`3xxx` 企微接口类、`4xxx` 内部状态类。

---

## 11. 非功能要求

| 项 | 要求 |
|---|---|
| 回调响应 | ≤ 3 秒返回，超 5 秒企微判定失败并重试 |
| 轮询间隔 | 5 秒，单批 100 条 |
| 任务延迟精度 | 允许 ±1 分钟 |
| 全量对账 | 每日一次，凌晨低峰 |
| 日志 | 所有企微调用记录请求/响应摘要与耗时 |
| 审计 | 群发、删除类操作必须留痕 |
| 安全 | 回调验签；appSecret 走环境变量，不入库不入库代码 |

---

## 12. 风险与限制

| 风险 | 说明 | 应对 |
|---|---|---|
| 群发无法全自动 | 接口设计如此，员工必须确认 | 产品定位为"半自动"，不承诺全自动 |
| 外部联系人上限 | 未认证企业上限较低 | 尽早完成主体认证 |
| 欢迎语 20 秒窗口 | 回调处理慢就超时 | 同步执行 + 5 秒超时 + 失败降级 |
| 标签数量上限 | 企微标签有总量限制 | 标签命名规范，定期清理 |
| 政策变动 | 接口规则可能调整 | gateway 层隔离，改一处即可 |

---

## 13. 交付分期

### 阶段一：接入与客户资产

**交付物**：回调服务、客户同步、欢迎语、自动打标、客户列表页。

**验收标准**：用测试企业扫码加好友，5 秒内收到欢迎语，后台能看到该客户及来源标签。

### 阶段二：规则与提醒

**交付物**：规则配置、条件求值、任务生成、跟进提醒、看板。

**验收标准**：配置"添加满 1 天 → 提醒跟进"，次日后台出现该待办，完成后看板完成率更新。

### 阶段三：群发任务

**交付物**：人群圈选、群发任务创建、配额控制、结果回查。

**验收标准**：同一客户当天第二次群发被置 `skipped`；员工在企微确认后，系统状态变为 `sent`。

### 阶段四：打磨

**交付物**：全量对账、失败重试优化、审计日志、数据导出。

---

## 14. 已确认事项与待确认问题

### 14.1 已确认

| 编号 | 结论 | 对设计的影响 |
|---|---|---|
| Q1 | 营业执照未办；已用手机号注册企微企业，处于未验证主体状态 | 可立即开发联调，不依赖执照；但外部联系人上限低，群发等权限需实测 |
| Q2 | 目前没有客户来源 | 阶段一不做渠道二维码与来源标签；标签先用默认标签 + 手动标签 |
| Q3 | 只有 1 个销售（本人） | 不做任务分配，`mk_task` 不需要 `assignee` 字段 |
| Q4 | 无历史客户数据 | 不做批量导入与去重 |
| Q5 | 暂不需要模板管理 | 话术走配置文件，不做模板 CRUD 与素材库 |

### 14.2 仍待确认

| 编号 | 问题 | 影响 | 当前处理 |
|---|---|---|---|
| Q6 | 何时办营业执照并完成主体验证 | 决定外部联系人上限与群发权限 | 先按未验证企业开发，接口按能力探测降级 |
| Q7 | 首批客户从哪里来 | 无输入则系统空转 | 先用自有微信作为测试客户跑通闭环 |
| Q8 | 未验证企业能否使用群发接口 | 决定阶段三是否可以实施 | 阶段三开工前用真实企业实测确认 |
