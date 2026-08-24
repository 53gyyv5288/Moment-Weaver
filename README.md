# Moment Weaver · 时光编织者

> AI 引导式家族口述史 / 个人时光胶囊 / 团队企业故事采集与生成平台。
> 个人独立开发，覆盖「账号/工作区/项目 → 人物授权 → AI 采访 → 时间线 → 素材 → 段落级 AI 标识 → 受控分享 → 数据导出与遗忘权」完整主链路。

![status](https://img.shields.io/badge/stage-M14%2B%20%E5%AE%9E%E9%AA%8C%E5%AE%A4-success) ![backend](https://img.shields.io/badge/backend-Spring%20Boot%203%20%2F%2013%20modules-blue) ![ai](https://img.shields.io/badge/AI-FastAPI%20%2F%20Milvus-orange) ![frontend](https://img.shields.io/badge/frontend-Vue%203%20%2F%20Element%20Plus-brightgreen)

---

## ✨ 已落地的能力

| 域                    | 能力                                                                                                                                                                                        |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **账号与协作**        | 手机/邮箱注册、JWT（Access + Refresh）、个人 workspace、**家族组织**（admin / editor / viewer 三角色 + 强制改密 + 成员代创建）                                                              |
| **项目与人物**        | family / personal 两种项目类型、Subject 档案、家族关系（generation / parentSubjectId / parentRelationType）                                                                                 |
| **合规授权**          | 一次性 token + IP/UA 鉴权、状态机 `pending → granted \| denied \| revoked \| expired`、`AuthorizationRevokedEvent` 事件级联清理 Milvus 向量 / 撤销分享 / 通知 owner                         |
| **可携带权 / 遗忘权** | 数据导出 zip 包、30 天软删 → 物理删除定时任务、审计日志                                                                                                                                     |
| **AI 采访**           | SSE 流式（首响 < 1.5s）、思考链（`<think>...</think>`）分流、turnId 锚定 PENDING/COMPLETED/FAILED 三态、MongoDB atomic `$push` + `$set` 拆两步规避冲突                                      |
| **短时记忆**          | Redis recent list + summary 文本双层、滚动摘要（K → K/2）、二次压缩（> 1500 字符 → 400-600 字）、Mongo 兜底 warmUp                                                                          |
| **RAG**               | Milvus hybrid search（dense + BM25 Function）+ reranker + small-to-big 回溯、3 场景配置（interview / timeline / narrative_facts）、跨 family 隔离 + 同 familyMember 共享、7s 软超时降级     |
| **AI 成稿**           | 模板化 Prompt 组装器（人物 6 章节 / 家族 5 章节）、段落级 `provenance ∈ {human, ai_generated, ai_rewritten, mixed}`、AI 标识强制不可关闭、4 种重写风格（warmer / concise / vivid / formal） |
| **家族树**            | 手写 BFS 布局 + 虚拟渲染（支持 100+ 代际）+ 缩略图导航 + 自研 pan/zoom + 环引用降级                                                                                                         |
| **分享**              | 三种 scope（public / password / internal）、BCrypt 密码、SecureRandom token、IP 限流（1 分钟 30 次）、`hasAiContent=true` 强制透出横幅                                                      |
| **心灵信箱**          | 跨代角色人格（persona）、代际关系提示词注入、独立会话通道、与主授权共享                                                                                                                     |
| **存储与上传**        | MySQL（强事务）/ MongoDB（文档）/ Redis（缓存+SSE 进度）/ Milvus（向量）/ OSS（对象），STS 签名直传 mock + real 双模式                                                                      |

---

## 🧱 技术栈

| 层          | 选型                                                          | 备注                                                                 |
| ----------- | ------------------------------------------------------------- | -------------------------------------------------------------------- |
| 前端        | Vue 3 + Vite + Element Plus（桌面）+ Vant（移动断点）         | Pinia / Vue Router 4 / Axios；CSS 变量驱动主题                       |
| 后端        | Spring Boot 3.x + **13 个 Maven module 模块化单体**           | 同进程 in-process 调用；不上 Spring Cloud / Nacos                    |
| ORM 与存储  | MyBatis-Plus（MySQL 8.0）+ Spring Data MongoDB 6.0 + Redis 6+ | Flyway 迁移：`db/migration/V1__init_schema.sql`                      |
| 安全        | Spring Security + JWT（Access/Refresh）+ BCrypt + AES         | `module-auth` 统一签发                                               |
| AI 后端     | FastAPI + Uvicorn + Pydantic v2                               | 单应用 + 12 个 router                                                |
| LLM         | OpenAI 兼容协议（DeepSeek / Qwen / 通用推理模型）             | 流式 + 思考链分流 + `chat_template_kwargs` 关推理                    |
| 向量库      | Milvus 2.x                                                    | dense + BM25 Function + rerank + small-to-big                        |
| 消息 / 异步 | Spring `ApplicationEventPublisher` + Redis Streams + SSE      | 跨模块事件 + 长连接进度回写                                          |
| 对象存储    | 阿里云 OSS（STS 签名直传）+ CDN                               | mock / real 双模式 STS                                               |
| 部署        | Docker Compose（仅生产）                                      | nginx + backend + ai + mysql + mongo + redis + milvus + etcd + minio |

---

## 📂 项目结构

```
moment-weaver/
├── backend/                        # Spring Boot 3 模块化单体
│   ├── moment-weaver-app/          # 应用入口
│   ├── module-account/             # 账号 / 工作区 / 项目 / 家族
│   ├── module-auth/                # JWT 鉴权 + Spring Security
│   ├── module-common/              # Result / Exception / event 基础类型
│   ├── module-compliance/                # 授权 / 分享 / 导出 / 删除 / STS
│   ├── module-export/              # PDF 导出
│   ├── module-heartcove/           # 心灵信箱（代际关系人格）
│   ├── module-memory/              # 人物 / 采访 / 素材 / STM / RAG 客户端
│   ├── module-notification/        # 站内通知
│   ├── module-rag/                 # RAG ingest / retrieve 客户端
│   ├── module-share/               # 公开分享链接
│   ├── module-timeline/            # 时间线 / 成稿 / AI 叙事客户端
│   └── pom.xml
├── ai/                             # FastAPI 单应用
│   ├── app/
│   │   ├── main.py
│   │   ├── routers/                # 12 个路由：interview / narrative / summarize ...
│   │   ├── services/               # llm / prompts / templates / json_extract ...
│   │   ├── rag/                    # pipeline_ingest / pipeline_retrieve / milvus ...
│   │   └── prompts/                # 提示词集中管理
│   └── pyproject.toml
├── frontend/                       # Vue 3 + Element Plus + Vant
│   └── src/
│       ├── views/                  # 业务视图（auth / project / interview / family / heartcove ...）
│       ├── components/             # ProvenanceBadge / NotificationBell
│       ├── stores/                 # Pinia
│       ├── router/
│       ├── api/
│       └── styles/global.css       # CSS 变量 + Element Plus 主题覆盖
├── db/
│   ├── init/                       # MySQL 初始 schema（兼容本地建库）
│   └── migration/                  # Flyway 迁移
├── deploy/                         # 生产部署
│   ├── docker-compose.yml          # nginx + 8 个中间件 + 后端
│   ├── .env / env.example
├── docs/
│   └── FAMILY_USAGE.md             # 家族组织使用说明
├── PRD.md                          # 产品需求（根目录）
├── DevelopmentPlan.md              # 开发计划（根目录）
├── scripts/
│   ├── check-env.ps1               # 本地环境自检
│   ├── start-all.ps1               # 一键启动
│   └── e2e-family.ps1              # 家族组织 e2e 验证
└── README.md
```

---

## 🚀 本地开发

### 环境前置

| 组件    | 版本                                    | 用途                         |
| ------- | --------------------------------------- | ---------------------------- |
| JDK     | 17（与 JDK 8 共存，IDE 切到 17）        | 后端编译运行                 |
| Maven   | 3.9.9                                   | 后端构建                     |
| Node.js | 20 LTS                                  | 前端 Vite                    |
| pnpm    | latest                                  | 前端包管理（推荐）           |
| Python  | 3.11                                    | AI 服务                      |
| MySQL   | 8.0（admin/1234，**仅本地**）           | 业务强事务                   |
| MongoDB | 6.0                                     | 文档 / 采访消息 / 成稿       |
| Redis   | 6+                                      | 缓存 / SSE 进度 / STM recent |
| Milvus  | 2.x（可选，本地用 docker-compose 启动） | RAG 向量库                   |

> ⚠️ **安全警告**：`admin/1234` 仅用于本地 MySQL，**严禁**入 git；LLM Key 通过 `.env` 注入，**严禁**入 git。

### 一键启动（推荐）

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-env.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-all.ps1
```

打开 <http://localhost:5173/health-check>，三件套状态全绿 = 主链路可用 ✅

### 手动启动

```powershell
# 第 1 步：建库（首次）
mysql -u admin -p1234
source db/init/01-schema.sql

# 第 2 步：启动后端（新窗口）
cd backend
mvn -pl moment-weaver-app -am spring-boot:run
# 健康检查   http://localhost:8080/api/v1/healthz
# 依赖自检   http://localhost:8080/api/v1/readyz
# API 文档   http://localhost:8080/swagger-ui.html

# 第 3 步：启动 AI（新窗口）
cd ai
pip install -e .
copy .env.example .env       # 填入 DEEPSEEK_API_KEY 等
uvicorn app.main:app --reload --port 8000
# 健康检查   http://localhost:8000/healthz
# API 文档   http://localhost:8000/docs

# 第 4 步：启动前端（新窗口）
cd frontend
pnpm install
pnpm dev
# 访问       http://localhost:5173/
# 自检页     http://localhost:5173/health-check
```

### 端到端验证（家族组织）

```powershell
powershell -ExecutionPolicy Bypass -File scripts/e2e-family.ps1
```

---

## 🧪 关键设计要点

### 模块化单体

13 个 Maven module 同进程部署，跨模块调用走 `ApplicationEventPublisher` 事件解耦（`TimelineEventRequest` / `AuthorizationRevokedEvent` / `InterviewMessageAppendedEvent` / `NotificationRequest`），规避循环依赖。`ObjectProvider<IAcsClient>` 模式保证 STS 配置缺失时不阻塞启动。

### 合规授权与撤回级联

- 一次性 token（SecureRandom + 24 字节 base32）+ IP/UA/版本号取证；
- 状态机强约束在 MySQL 事务中；
- 撤回事件 → timeline / share / notification / RAG（清理 Milvus 向量）各自监听级联处理；
- 同 familyMember 多 subject 共享授权（V15 增强）。

### 流式 AI 采访

- **SSE 多事件流**：`event: token / thinking / error / done`，Spring `WebClient` + `StreamChunk` 转发给浏览器；
- **思考链分流**：状态机 + buffer 处理 LLM 把标签切到多个 token 的边界情况；
- **首字节前 1 次重试**：连接错误 / 5xx / 429 才重试，**已 yield 不重试**（避免用户看到重复内容）；
- **STM 压缩**：Redis recent + summary 双层，超过阈值触发滚动摘要 + 二次压缩；
- **MongoDB atomic `$push` + `$set` 拆两步**：规避 Mongo "conflict at 'messages'" 错误；
- **turnId 三态机**：PENDING → COMPLETED / FAILED，前端可据此显示"发送中 / 已完成 / 未回复"。

### RAG 防幻觉

- **5 阶段管线**：query rewrite → embed → hybrid search (dense+BM25) → rerank → small-to-big 回溯；
- **3 场景配置**：interview / timeline / narrative_facts（仅 `is_curated_for_facts == true`）；
- **跨 family 隔离 + 同 familyMember 共享**：每条 chunk 写入时携带 `family_id / family_member_id`；
- **7s 软超时降级**：失败返回空 evidence（不阻塞首字）；
- **Prompt 强约束**：`factsUsed` 必须是输入 fact_id 子集，不可编造 fact_id。

### 段落级 AI 标识

- 数据层 `NarrativeDraft.Section` 内嵌 `provenance ∈ {human, ai_generated, ai_rewritten, mixed}` + `factsUsed` + `rewriteCount`；
- 模板化 Prompt 组装器：2 套模板（人物 6 章节 / 家族 5 章节）；
- JSON 强约束输出（禁止 ASCII 半角双引号）；
- 强制渲染不可关闭，覆盖生成式 AI 合规要求；
- 乐观锁 + `If-Match: <version>` 头做并发编辑冲突检测。

### 家族树可视化

- 手写 BFS 布局 + 同层 id 排序 + bbox 计算；
- 虚拟渲染：仅渲染当前视口可见 ±1 代际，支持 100+ 代际；
- 缩略图（minimap）始终显示全图 + 视口矩形同步；
- 手写 pan/zoom：以光标为中心缩放（保留 cursor 下的 world 点不动）；
- 环引用 / 脏数据 → 降级为扁平列表 + 警告 banner。

### OSS STS 直传

- mock + real 双模式（开发 / 生产），通过 `stsMode` 配置切换；
- `ObjectProvider<IAcsClient>` 防真实模式配置缺失时启动挂；
- 前端拿 STS → 直接 PUT 到 OSS，服务端不中转二进制。

---

## 📜 文档导航

| 文档                                         | 内容                                        |
| -------------------------------------------- | ------------------------------------------- |
| [PRD.md](PRD.md)                             | 产品需求基线（场景、功能、非功能、AI 规范） |
| [DevelopmentPlan.md](DevelopmentPlan.md)     | 开发计划与里程碑（WBS）                     |
| [docs/FAMILY_USAGE.md](docs/FAMILY_USAGE.md) | 家族组织使用说明（M10+）                    |

外部技术总结（在 `C:\Learning\Markdown\`）：

- `MomentWeaver-技术简历总结.md` — 详尽版（10 模块 + ADR + 面试问答）
- `Moment Weaver-技术简历总结（精简版）.md` — 简历版（7 条要点 + 量化表）

---

## 🛣️ 当前阶段

**主线已跑通**：注册 → 建项目 → 添加人物 → 授权 → 采访 → 生成小传 → 分享。

| 阶段                         | 状态 | 说明                                          |
| ---------------------------- | ---- | --------------------------------------------- |
| M0 立项与基线                | ✅   | PRD / DevPlan / 仓库骨架 / 三件套 Hello World |
| M1 账号 / 工作区 / 项目骨架  | ✅   | JWT / 注册 / 登录 / 找回                      |
| M2 人物 / 授权 / 采访        | ✅   | 一次性 token 授权 + AI 采访                   |
| M3 素材 / 时间线             | ✅   | OSS 直传 + EXIF + 时间线聚合                  |
| M4 成稿 / AI 标识            | ✅   | 2 模板 + 段落级 provenance + 强制不可关闭     |
| M5 分享 / 合规 / 通知 / Vant | ✅   | 受控分享 + 数据导出 + 30 天软删               |
| M6 试运行 + 修复             | 🔄   | 邀请种子用户跑通主链路                        |
| **M7+ STM 滚动摘要**         | ✅   | Redis recent + summary 双层 + 二次压缩        |
| **M8+ turnId 三态机**        | ✅   | PENDING/COMPLETED/FAILED + 乐观更新           |
| **M9+ Adaptive RAG**         | ✅   | LLM 判定是否需要检索 + 策略字段               |
| **M10+ 家族组织**            | ✅   | 多成员协作 + 强制改密                         |
| **M11 Phase 3 代答模式**     | ✅   | userA 代答匿名 subject + canStream 守卫       |
| **M12+ self-grant**          | ✅   | 自己授权自己 + 兄弟 subject 共享              |
| **M14+ 家族关系图**          | ✅   | BFS + 虚拟渲染 + 缩略图                       |
| **RAG（Milvus 落地）**       | ✅   | hybrid search + 跨 family 隔离                |
| **心灵信箱**                 | ✅   | 代际关系提示词注入                            |

**二期优先序**（按 ROI）：微服务拆分（团队扩到 3 人时） → RAG + 知识图谱 → 微信小程序端 → 邮件 / Web Push → 团队项目 + 企业版 → 图像/视频生成（GPU）。

---

## 📄 License

个人学习项目，保留所有权利。
