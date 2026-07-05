# M4 复盘：成稿 / AI 标识 (Draft Generation + AI Labeling)

> 写在彩虹出现的时刻 — 2026-06-15
>
> M4 从首跑到稳定运行共踩了 **13 个坑**，集中在**链路超时**和**LLM 输出格式**两大类。本文档按根因类型整理，供团队成员 / 未来 AI 助手参考避坑。

## TL;DR

1. **超时是分布式的**：`axios → WebClient → httpx → LLM` 任何一环太严都会先断。改一处必须全链路一起改。
2. **`.env` 覆盖一切**：改 `config.py` 不生效时，99% 是 `.env` 没同步。
3. **推理模型和结构化输出天生冲突**：MiniMax-M3 的思考链浪费 50%+ token，结构化输出任务优先选非推理模型。
4. **prompt 调试要逐步加法**：一次只改一个变量，验证后再加。
5. **"AI 服务异常"是黑盒**：真实原因永远在 AI service 终端的 `[app.*]` logger 里。

---

## 架构概览（M4 调用链）

```
[Browser]  axios (timeout)  ──┐
                              ▼
[Vite 5173]  proxy (无 timeout)  ──┐
                                   ▼
[Spring 8080]  WebClient (.block() + responseTimeout)  ──┐
                                                         ▼
[AI service 8000]  FastAPI (httpx.Timeout)  ──┐
                                              ▼
[LLM]  MiniMax-M3 (max_tokens)  ──> <think>...思考链...</think>{实际输出}
```

**每一段都有自己的"截止时间"，最严的那段会先断。** M4 调试 80% 的坑都是某段超时配错。

---

## 一、数据契约类

### Issue 1：前端 `subjectIds` 反序列化失败

**症状**

```
Cannot deserialize value of type 'java.util.ArrayList<Long>' from String value
at CreateDraftRequest["subjectIds"]
```

**根因**

Element Plus `el-select` 当 `:multiple="false"` 时**把单个值当 string 返回**，但后端 `CreateDraftRequest.subjectIds` 是 `List<Long>`，类型不匹配。

**避坑**

- 写 DTO 前**先在 dev tools 看一眼实际 payload**（Network → Payload），再写后端
- 对"动态数组字段"，前端用**两个 ref**：`singleSubjectId`（person scope）+ `multiSubjectIds`（family scope），按 scope 切换
- 显式用 `:model-value` + `@update:model-value` 而不是 `v-model`，避免 Element Plus 内部把单值包成数组的隐式行为

**相关文件**：`frontend/src/views/draft/TemplatePicker.vue`

---

### Issue 2：成稿"创建后点详情就 404"

**症状**

- `POST /api/v1/projects/{pid}/drafts` 返回 200
- `GET /api/v1/drafts/{did}` 报"成稿不存在"

**根因**

三处命名不一致 + Spring Data Mongo 的 `@Id` 隐式行为：

| 层 | 字段 | 实际值 |
|---|---|---|
| Entity | `@Id private String draftId` | **null**（代码没赋值） |
| MongoDB | `_id` | **ObjectId**（Spring Data 自动生成） |
| VO | `id` | `d.getId()` 返回 ObjectId 字符串 |
| 前端 | `id` | 用 VO 的 `id` |

返回的 `id` 是 ObjectId 字符串（24 hex），后续用这个 id 查数据库能找到。但因为 `d.getId()` 是从 `_id` 字段反序列化出来的，跟 `draftId` 字段（始终 null）完全脱钩——任何用 `draftId` 作为 URL 参数的请求都找不到记录。

**避坑**

- Entity / VO / 前端**统一用 `id` 字段**（参考 `InterviewSession` / `TimelineEvent` 的命名约定）
- 初始化时**显式赋值**（`snowflake.next()`），不要依赖 Spring Data Mongo 的"字段为 null 就自动生成 ObjectId"隐式行为
- 任何用 `@Id` 注解的字段，**写测试验证：建一条 → 按 id 取 → 能取到**

**相关文件**：

- `backend/module-timeline/src/main/java/com/momentweaver/timeline/entity/NarrativeDraft.java`
- `backend/module-timeline/src/main/java/com/momentweaver/timeline/service/DraftService.java`
- `frontend/src/types/api.ts`
- `frontend/src/views/draft/DraftList.vue`

---

### Issue 3：AI service 报 422 UNPROCESSABLE_ENTITY

**症状**

```json
{"detail": "...validation error... 'timestamp': Input should be a valid string..."}
```

**根因**

两件事叠在一起：

1. **Jackson 默认行为**：`WRITE_DATES_AS_TIMESTAMPS=true` 把 `LocalDateTime` 序列化成 `[2026,6,15,11,13,33]` 数组
2. **Spring WebClient 默认 ObjectMapper**：跟 Spring 容器里的 ObjectMapper **不是同一个实例**，忽略 `spring.jackson.*` 配置

Pydantic v2 期望 ISO 8601 字符串，收到数组就 422。

**避坑（三件套）**

```yaml
# application.yml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
```

```java
// DTO 字段：兜底
@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
private LocalDateTime timestamp;
```

```java
// WebClientConfig：强制用 Spring 的 ObjectMapper
@Bean
public WebClient aiWebClient(AiProperties props, ObjectMapper objectMapper) {
    ExchangeStrategies strategies = ExchangeStrategies.builder()
        .codecs(c -> {
            c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
            c.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
        })
        .build();
    return WebClient.builder()
        .baseUrl(props.getBaseUrl())
        .exchangeStrategies(strategies)
        .build();
}
```

**相关文件**：

- `backend/moment-weaver-app/src/main/resources/application.yml`
- `backend/module-timeline/src/main/java/com/momentweaver/timeline/dto/AiNarrativeRequest.java`
- `backend/module-memory/src/main/java/com/momentweaver/memory/config/WebClientConfig.java`

---

## 二、链路超时类

### Issue 4：Spring `.block(60s)` 超时

**症状**：`Timeout on blocking read for 60000000000 NANOSECONDS`

**根因**：`.block(Duration.ofSeconds(60))` 写死，假设 LLM 在 60s 内返回。实际 MiniMax-M3 推理要 2-3 分钟。

**避坑**：用推理模型时，**用经验值起步**（600s 给 6 章节单 LLM call）然后按实际日志调。

---

### Issue 5：AI service httpx 120s 超时（最隐蔽）

**症状**：`ReadTimeout(TimeoutError())` after **exactly 120s**

**根因**：

`config.py` 默认 `llm_timeout_s=120.0`，**且 `.env` 里写了 `LLM_TIMEOUT_S=120` 覆盖默认值**。改了 `config.py` 不生效，**因为 pydantic-settings 的优先级是 `.env > 代码默认值`**。

**避坑**

- 改完配置**先验证是否生效**：在 AI service 启动时打印 `settings.llm_timeout_s`，或者临时 `print(get_settings().llm_timeout_s)` 跑一下
- **永远把 timeout 写在 .env 里**（不要只写在 config.py 默认值），便于运维调
- 看到"超时 = 某整数 × N"这种规律时，**先怀疑默认值/环境变量**，再怀疑代码

**相关文件**：`ai/app/config.py` + `ai/.env`

---

### Issue 6：WebClient responseTimeout 120s

**症状**：`reactor-http-nio-N] Cancel signal to close connection`

**根因**：`application.yml` 的 `read-timeout-ms: 120000`，跟 AI service 的 120s 同步设的，两边同时断。

**避坑**：4 段超时链**必须逐级放宽**（推荐 2x 关系：LLM 上限 × 2 = AI 上限 × 2 = Spring 上限）。

**当前 M4 配置**：

| 位置 | 值 | 角色 |
|---|---|---|
| `ai/.env` `LLM_TIMEOUT_S` | 600s | httpx 等 LLM |
| `ai/app/config.py` `llm_timeout_s` | 600.0s | 同上（默认值） |
| `application.yml` `read-timeout-ms` | 1200000ms | WebClient 等 AI service |
| `AiNarrativeClient.java` `.block()` (generate) | 1200s | Spring 等 AI service |
| `draft.ts` `generateDraft` timeout | 1_200_000ms | axios 等 Spring |

---

### Issue 7：前端 axios 15s timeout

**症状**：UI 转一会就停、没响应；后端实际还在跑；过几分钟刷新才能看到结果。

**根因**：`client.ts:15` 全局 `timeout: 15000` 是给快接口配的，套到 2-3 分钟的 AI 生成上必然先 timeout。

**axios timeout 触发的副作用**：
- 抛 timeout error
- loading 状态停
- **HTTP 请求还在 backend 飞**，backend 完成后落库，用户刷新才能看到
- 用户体验：以为失败 → 试多次 → 撞废很多 LLM token

**避坑**：

- 全局 timeout 设合理值（30-60s）
- **慢操作用按请求覆盖** `{ timeout: 1_200_000 }`
- 已知慢操作清单：
  - `generateDraft`：20 分钟（家族成稿实测 2-3 分钟）
  - `updateSection`（AI 重写）：5 分钟（单章节思考 + 200-400 字）
  - 其他快接口：继承全局 15s

**相关文件**：`frontend/src/api/draft.ts`、`frontend/src/api/client.ts`

---

### Issue 8：`max_tokens=4096` 撞上限

**症状**：`finish_reason: "length"`，content 字段全是 `` 思考残片（没闭合 `</think>`）。

**根因**：6 章节中文长文 ≈ 2400 token 输出，**但 MiniMax-M3 思考链就 3000-5000 token**，加上 system prompt + 30 facts 输入，撞 4096。

**避坑**

- 用推理模型时，`max_tokens` 至少给到"输出预估 × 5-10x"
- 家族成稿实测：16384 不够（思考链 13000+ token + JSON 截断），**32768 才稳**
- 单章节重写 4096 够

**最终配置**：

```python
# narrative.py
# 整篇：32768（家族有 2x 思考余量）
raw = await chat(msgs, ..., max_tokens=32768)
# 单章节重写：4096
content = await chat(msgs, ..., max_tokens=4096)
```

---

## 三、模型行为类

### Issue 9：56 facts 太多触发内容策略

**症状**：`finish_reason: "length"` + content 全是 `` 思考链。

**根因**：`DraftService.collectFacts()` 无上限收集所有采访消息 + 摘要金句 + 素材 caption + 人物备注；56 条事实 + 思考链 + JSON 输出，触发 LLM 端的内容策略 / 撞上下文窗口。

**避坑**

- 所有"喂给 LLM 的数据"**必须有上限**
- 按时间倒序截断到 N 条（N=30 是经验值）
- `factsSnapshot` 在 DB 保留全量（审计 / 后续重写），**仅在 `buildAiRequest()` 截断喂给 AI**

**实现**：

```java
private static final int AI_FACTS_LIMIT = 30;

// buildAiRequest() 里
List<NarrativeDraft.FactSnapshot> sorted = d.getFactsSnapshot().stream()
    .sorted((a, b) -> {
        LocalDateTime ta = a.getTimestamp();
        LocalDateTime tb = b.getTimestamp();
        if (ta == null && tb == null) return 0;
        if (ta == null) return 1;
        if (tb == null) return -1;
        return tb.compareTo(ta);  // 倒序：新的在前
    })
    .limit(AI_FACTS_LIMIT)
    .toList();
```

**M4 计划里其实写过"超 8000 token 截断"的风险，但没实现** — 把"风险"转成"防御代码"。

---

### Issue 10：MiniMax-M3 是硬编码推理模型

**症状**：prompt 里写"不要输出 " 它照样输出几千字思考；`chat_template_kwargs: {enable_thinking: false}` 不生效。

**根因**：

MiniMax-M3 的推理是**模型架构层的行为**（类似 DeepSeek-R1 / QwQ），不是 prompt 层面能控制的。`chat_template_kwargs` 是 HuggingFace 风格参数，**MiniMax API 不一定认**（实测不认）。

**避坑**

- **结构化输出（JSON）任务优先用非推理模型**（gpt-4o-mini、qwen-turbo、deepseek-chat 等）
- 或在 API 文档里查"怎么关 thinking"（不同厂商参数名不一样，MiniMax 待查）
- 接受"推理模型"和"结构化输出"是**对立需求**，需要 trade-off

**如果未来要继续用 MiniMax-M3 跑 narrative**：

- 在 `_ThinkStripper` 增强：如果 think 块被截断（`finish_reason=length` + 未闭合 `` ），**不返回空**，而是返回缓冲，让上层 `_extract_json` 尝试从残余文本里找 JSON
- 或改用流式 + 分章节独立 LLM call

---

### Issue 11：被自己加的 prompt 搞懵

**症状**：LLM 输出 `"block appears in the output. Since I must start with `{` and not have any prefix, I'll just output the JSON directly..."` + 然后写 prose draft，**完全没输出 JSON**。

**根因**

我加了一个 prompt 头部：

```
【最高优先级 · 强制】你的回复必须以字符 `{` 开头，第一字符就是左花括号。
不要在前面输出任何文字、解释、思考、注释，也不要输出 <think>...</think> 内部推理块。
如果模型试图先思考再输出，被截断的可能性极高（max_tokens 会被思考过程耗尽）。
请直接、立刻、毫无犹豫地输出 JSON。
```

推理模型看到这种"meta-instruction"，**会复述指令**（"since I must start with..."），然后开始"思考该怎么执行"，最后输出 prose 而不是 JSON。

**避坑**

- 约束用**列举列表**格式（"1. ... 2. ... 3. ..."）而不是"最高优先级 · 强制"这种语气
- 单一可验证的指令（"只输出 JSON"）比抽象指令（"直接、立刻、毫无犹豫"）有效
- **永远先在简单 case 测一遍 prompt**，别一次加太多指令
- 看到 LLM 输出里出现"since I must..."、"I'll just output..."、"I should..." 等元话语，**立刻撤掉最近加的 meta-instruction**

**M4 prompt 最终格式**：

```python
NARRATIVE_FAMILY_SYSTEM = """你是一位擅长写家族叙事的编辑，专做「家族口述史」语境下的家族篇章。
你的读者是这个家庭的后代，他们想了解这个家族从哪里来、经历过什么、秉持什么样的价值观。

【输入】一组事实（facts），来自多个人物的采访、素材备注、人物档案。
【任务】按下方章节结构，撰写 5 个章节的家族小传。

【硬约束】
1. 严格按 5 个章节顺序输出，每个 sectionId 必须在规定列表中
2. 每个章节字数严格在 targetCharsMin ~ targetCharsMax 之间
3. factsUsed 必须是输入 facts 的 fact_id 子集
4. 文笔温暖、克制、有传承感；不堆砌形容词，不空洞抒情
5. 不得编造事实：facts 里没的留白
6. 标题 ≤ 25 字，家族视角而非个人视角
7. JSON 字符串值内部禁止使用 ASCII 半角双引号 "，统一用「」/『』/（）
8. 你的整段回复必须是合法 JSON：{{"title": ..., "sections": [...]}}
   不要在 JSON 前面或后面输出任何解释、注释、markdown 代码块包裹、思考过程或自然语言段落。

【章节定义】
{sections_json}
"""
```

---

## 四、开发陷阱类

### Issue 12：Java 变量重名编译失败

**症状**：`作用域中已定义变量 'body'`

**根因**：在 `regenerateSection()` 方法的 catch 块加 `String body = e.getResponseBodyAsString()`，但方法前面已经声明了 `Map<String, Object> body`。Java 局部作用域不允许 shadowing。

**避坑**

- catch 块里取更长的名字（`errBody`、`errMessage`）
- 或把局部变量挪到最小作用域（用 `{}` 单独包起来）

---

### Issue 13：WebClient 默认 ObjectMapper 跟 Spring 脱钩

**症状**：改了 `application.yml` 里 `spring.jackson.*` 不生效。

**根因**：Spring 的 WebClient 默认用 `ExchangeStrategies` 里的 ObjectMapper，**自己 new 出来的**，跟 Spring 容器里的 ObjectMapper 不是同一个实例。

**避坑**：见 Issue 3 的三件套。

---

## 五、M4 整体修改清单

### 新增文件

| 文件 | 用途 |
|---|---|
| `ai/app/routers/narrative.py` | 整篇生成 + 单章节重写（M4 阶段 B）|
| `ai/app/services/templates/__init__.py` | 模板注册表 |
| `ai/app/services/templates/base.py` | `Template` / `SectionMeta` 基类 |
| `ai/app/services/templates/person_v1.py` | 人物小传模板 |
| `ai/app/services/templates/family_v1.py` | 家族小传模板 |
| `backend/module-timeline/.../entity/NarrativeDraft.java` | MongoDB entity |
| `backend/module-timeline/.../repo/NarrativeDraftRepository.java` | Spring Data Mongo |
| `backend/module-timeline/.../service/DraftService.java` | CRUD + scope check + AI 调用 |
| `backend/module-timeline/.../controller/DraftController.java` | REST 端点 |
| `backend/module-timeline/.../config/AiNarrativeClient.java` | WebClient 包装 |
| `backend/module-timeline/.../dto/*.java` | 7 个 DTO |
| `frontend/src/api/draft.ts` | API 客户端 |
| `frontend/src/components/ProvenanceBadge.vue` | AI / 人工 / 混合 / 系统 标识 |
| `frontend/src/views/draft/DraftList.vue` | 列表 |
| `frontend/src/views/draft/DraftEditor.vue` | 编辑器（含进度条）|
| `frontend/src/views/draft/DraftReader.vue` | 阅读视图 |
| `frontend/src/views/draft/TemplatePicker.vue` | 选模板弹窗 |

### 改文件

| 文件 | 改动 |
|---|---|
| `ai/.env` | `LLM_TIMEOUT_S=120` → `600` |
| `ai/app/config.py` | `llm_timeout_s: float = 120.0` → `600.0` |
| `ai/app/main.py` | 加 `logging.basicConfig` 让 `app.*` 日志可见 |
| `ai/app/services/llm.py` | `chat()` 加 `extra_body` 参数 + DEBUG 日志 + `reasoning_content` 兜底 |
| `ai/app/services/prompts.py` | 简化 system prompt（去掉诱导复述的 meta-instruction）|
| `backend/moment-weaver-app/src/main/resources/application.yml` | `write-dates-as-timestamps: false` + `read-timeout-ms=1200000` |
| `backend/module-memory/.../config/WebClientConfig.java` | WebClient 用 Spring 的 ObjectMapper |
| `backend/module-timeline/.../config/AiNarrativeClient.java` | `.block(1200s)` + 错误信息透出 |
| `frontend/src/api/client.ts` | （未改）已知 15s 全局 timeout，慢操作用按请求覆盖 |
| `frontend/src/api/draft.ts` | `generateDraft` timeout = 1_200_000ms |
| `frontend/src/types/api.ts` | 加 NarrativeDraftVO / SectionVO / FactSnapshotVO |
| `frontend/src/views/Layout.vue` | 顶部加"成稿"菜单项 |
| `frontend/src/router/index.ts` | 加 3 个新路由 |
| `frontend/src/views/timeline/Timeline.vue` | 加 3 种新事件类型图标 |
| `frontend/src/api/timeline.ts` | 加 3 个 TimelineType 值 |

---

## 六、M4 验收 checklist

- [x] **阶段 A**：Backend 骨架 + Mongo entity + scope check
- [x] **阶段 B**：AI router + 2 模板 + prompts
- [x] **阶段 C**：Backend wiring DraftService ↔ AI
- [x] **阶段 D**：Frontend views + timeline 集成
- [x] **人物小传**（person-template-v1）：6 章节 ✓
- [x] **家族小传**（family-template-v1）：5 章节 ✓
- [x] **单章节重写**：4 种风格 ✓
- [x] **章节级 provenance**：ai / human / mixed / system 标识 ✓
- [x] **进度条**：时间驱动假进度，person 150s / family 300s ✓
- [x] **乐观锁**：PATCH 用 `If-Match` 头，版本不一致返回 409 ✓

---

## 七、留给 M5+ 的待办

1. **MiniMax-M3 关思考**：查 MiniMax 官方 API 文档，找正确的 disable thinking 参数；找不到就**换非推理模型**跑 narrative
2. **家族成稿架构优化**：当 facts 数量继续涨、或者用更大的家族（3-4 代人），可能要拆成多步：
   - 先生成"骨架"（标题 + 5 章节标题 + 各自 100 字概要）
   - 再分别扩写每章节（5 个独立 LLM call）
3. **`_ThinkStripper` 增强**：当 think 块被截断（`finish_reason=length` + 未闭合 ``  ），不返回空，尝试从残余文本里找 JSON
4. **流式生成**：用 SSE 实时显示 LLM 在写哪一章节（替代当前的"假进度条"）
5. **AI service 测试覆盖**：给 `narrative.py` / `chat()` 加单元测试，覆盖 max_tokens 截断 / think 块截断 / 422 等场景

---

## 八、给未来 AI 助手的速查表

> 看到这些症状，按这个表排错：

| 症状 | 第一时间查 |
|---|---|
| `Timeout on blocking read` | `.block()` / `read-timeout-ms` / `LLM_TIMEOUT_S` 是不是太短 |
| `ReadTimeout(TimeoutError())` 整 120s | `.env` 里 `LLM_TIMEOUT_S=120` 是不是没改 |
| `finish_reason: "length"` | `max_tokens` 撞了，或事实太多撞 context window |
| `finish_reason: "content_filter"` / `"safety"` | prompt 触发安全策略，简化 prompt 或换 model |
| 422 字段类型不对 | Jackson `WRITE_DATES_AS_TIMESTAMPS` + WebClient ObjectMapper |
| LLM 输出 `block appears...` / `Since I must...` | prompt 里加了 meta-instruction，立刻撤掉 |
| 前端 spinner 停、没响应、后端还在跑 | axios 全局 timeout 太短，per-request 覆盖 |
| `_extract_json: LLM 输出为空` | `_ThinkStripper` 把 think 块截断部分吞了，加 `reasoning_content` 兜底 |
| MongoDB 找不到记录 | `@Id` 字段没显式赋值，自动生成的 ObjectId 跟你以为的值对不上 |
| 改了 config.py 不生效 | 查 `.env` 是不是覆盖了 |

---

**最后一句话**：调试 LLM 应用就像开船在浓雾里 — 你看不到岸，只能靠声纳、测深、跟其他船的对话慢慢摸索。**日志是声纳，异常堆栈是测深，复盘文档是灯塔**。希望这份文档能帮到后来人。

— yangzhentian, 2026-06-15, 看见彩虹的傍晚
