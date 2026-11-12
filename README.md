# wecom-marketing

企业微信私域营销自动化系统。以「事件驱动 + 渠道解耦」为核心的营销引擎，把客户从加好友到成交的重复动作交给系统，把判断和成交留给人。

> **项目状态**：阶段一已完成（回调接入、客户档案、欢迎语、自动打标、客户列表）；营销旅程引擎、任务调度、群发配额等模块已完成设计（见 [SPEC](wecom-marketing-spec.md)），尚未实现。下文「已实现」与「Roadmap」严格区分。

---

## 一、为什么是「半自动化」

企业微信有三个无法绕开的硬约束，直接决定了系统的产品形态：

| 约束 | 具体表现 | 系统应对 |
|---|---|---|
| 不能主动私聊客户 | 除欢迎语外无主动单聊接口 | 只做被动接待 + 定时群发 |
| 群发需员工确认 | `add_msg_template` 只是把消息推给员工 | 系统产出「待办」，不直接发送 |
| 群发频率受限 | 每客户每天最多 1 条 | 数据库唯一索引硬约束配额 |

因此系统不会替人发消息，而是自动完成**识别、打标、排期、提醒**。

---

## 二、架构

```
          ┌──────────────────────────────────────────┐
          │             企业微信开放平台              │
          └───────┬──────────────────────┬───────────┘
      回调(事件)   │                      │  主动调用(API)
                  ▼                      ▼
        ┌──────────────────┐   ┌──────────────────┐
        │  callback 回调层  │   │   wecom 网关层    │
        │ 验签 / 解密 / 幂等 │   │ token / HTTP     │
        └────────┬─────────┘   └──────────────────┘
                 ▼
        ┌──────────────────┐
        │  contact 客户域   │  ← 建档、打标、来源
        └────────┬─────────┘
                 ▼
        ┌──────────────────┐
        │   event 事件流水  │  ← 幂等键唯一约束
        └──────────────────┘
```

模块职责：

| 模块 | 职责 |
|---|---|
| `callback` | 回调验签、AES 解密、XML 解析、事件分发 |
| `wecom` | 企微 API 网关：access_token 缓存、HTTP 调用、加解密 |
| `contact` | 客户档案、标签、来源归因 |
| `event` | 事件流水与幂等 |
| `api` | 对外的查询接口 |

---

## 三、已实现

| 能力 | 说明 |
|---|---|
| 回调验签 + 解密 | 自研 SHA1 签名校验与 AES-256-CBC（32 字节分块 PKCS7）解密，不依赖第三方 SDK |
| 事件幂等 | `ev_event.event_key` 唯一索引 + `DuplicateKeyException` 捕获，企微重投不会重复处理 |
| 客户建档 | 收到「添加好友」事件后拉取客户详情并落库 |
| 欢迎语 | 回调线程内**同步**发送，不重试；成功后写 `welcomed_at` 防重 |
| 自动打标 | 新客户自动打「新客户」标签，同步到企微企业标签 |
| 客户查询 | 分页查询客户列表 |

### 关键技术点

**1. 欢迎语的 20 秒窗口**

企微的 `welcome_code` 有效期只有 20 秒，超时无法补发。所以欢迎语必须在回调处理线程内同步完成，且失败不重试——重试必然失败，只会占用线程。这是「快路径」；其余动作走「慢路径」，落任务表后由执行器异步消费。

**2. Base64 中的 `+` 被解析成空格**

企微回调的 `echostr` 是 Base64 字符串，可能包含 `+`。若依赖 Web 容器做 URL 解码，`+` 会被转成空格，导致签名校验必然失败，且错误表现为「签名不匹配」，极易误导排查方向。

解决方式：从 `request.getQueryString()` 取原始字符串，只做百分号解码，不把 `+` 当空格处理：

```java
// WecomCallbackController#parseQuery
if (c == '%' && i + 2 < raw.length()) {
    out.write(Integer.parseInt(raw.substring(i + 1, i + 3), 16));
    i += 2;
} else {
    // 直接写入原字符，'+' 保持为 '+'
}
```

---

## 四、技术栈

| 项 | 选型 | 说明 |
|---|---|---|
| 语言 | Java 17 | |
| 框架 | Spring Boot 4.1 | Web MVC + Validation |
| 数据访问 | Spring JDBC (`JdbcClient`) | 未引入 ORM，SQL 可控 |
| 数据库 | MySQL 8 | |
| 加解密 | JDK 内置 AES / SHA1 | 无第三方 SDK 依赖 |

---

## 五、快速开始

### 1. 建库

```bash
mysql -u root -p < src/main/resources/db/schema.sql
```

### 2. 配置环境变量

密钥不写入代码与配置文件，全部通过环境变量注入：

```bash
export WECOM_CORP_ID=your_corp_id
export WECOM_AGENT_ID=your_agent_id
export WECOM_SECRET=your_secret
export WECOM_TOKEN=your_callback_token
export WECOM_AES_KEY=your_encoding_aes_key
export DB_USERNAME=root
export DB_PASSWORD=your_password
```

PowerShell：

```powershell
$env:WECOM_CORP_ID="your_corp_id"
$env:WECOM_SECRET="your_secret"
# ...
```

### 3. 启动

```bash
./mvnw spring-boot:run
```

### 4. 配置回调

企微管理后台 → 应用管理 → 自建应用 → 接收消息 → 设置 API 接收：

- URL：`https://<你的域名>/wecom/callback`
- Token / EncodingAESKey：与上一步环境变量保持一致

---

## 六、接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/wecom/callback` | 企微回调 URL 校验（验签后回显 echostr） |
| POST | `/wecom/callback` | 接收事件与消息，解密后分发 |
| GET | `/api/customers` | 分页查询客户列表 |

---

## 七、数据模型

| 表 | 用途 | 关键约束 |
|---|---|---|
| `ct_customer` | 客户档案 | `uk_ext(external_uid)` |
| `ct_tag` | 标签定义 | `uk_name(name)` |
| `ct_customer_tag` | 客户-标签关联 | `uk_customer_tag(customer_id, tag_id)` |
| `ev_event` | 事件流水 | **`uk_event_key(event_key)` 幂等核心** |

---

## 八、已知限制

1. **主体限制**：未认证企业 + 未备案域名无法配置回调 URL。企微要求回调域名完成 ICP 备案，且备案主体与企业主体一致或有关联。实测调用客户接口会返回 `60020 IP 不在白名单`，而可信 IP 又要求先配置回调 URL 或可信域名——三者互相前置，形成闭环。
2. **单实例**：`access_token` 缓存在进程内（`ConcurrentHashMap`），多实例部署需换成 Redis。
3. **无模板管理**：话术走配置文件，暂不支持后台配置。
4. **未做**：营销旅程引擎、任务调度、群发配额、SOP 编排。

---

## 九、Roadmap

| 阶段 | 内容 |
|---|---|
| 一 ✅ | 回调接入、客户档案、欢迎语、自动打标、客户列表 |
| 二 | 规则引擎、营销旅程、任务落库与轮询执行器 |
| 三 | 群发编排、配额控制、退订与频次风控 |
| 四 | 微信客服（kf）机器人接待、关键词路由、兜底转人工 |

---

## 十、文档

- [产品与技术 SPEC](wecom-marketing-spec.md)：产品定位、业务流程、数据模型、接口契约、风险与验收标准
