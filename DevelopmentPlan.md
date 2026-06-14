# Moment Weaver · 开发计划

> 文档版本：v1.1（单人 + Vibe Coding 瘦版）
> 周期：14 周（Web 端瘦版 MVP）
> 模式：**单人专注 + AI 辅助生成**，按里程碑 Gate 推进
> 配套：本文档配合 `PRD.md` 一起阅读

---

## 0. 总览

### 0.1 里程碑一览
| 阶段 | 周次 | 主题 | Gate 产物 |
| --- | --- | --- | --- |
| M0 | W1 | 立项与基线 | 需求评审、ADR、CI、本地起服务 |
| M1 | W2-W3 | 地基：账号/工作区/项目骨架 | 端到端"注册→建项目"跑通 |
| M2 | W4-W5 | 核心：人物/授权/采访 | 授权合规评审、AI 采访 demo |
| M3 | W6-W7 | 核心：素材/时间线 | 图片上传、时间线聚合 |
| M4 | W8-W9 | 核心：成稿 + AI 标识 | 2 模板跑通、标识规范落地 |
| M5 | W10-W11 | 外延：分享/合规中心/通知/Vant 适配 | 合规自检 0 critical |
| M6 | W12-W14 | 试运行 + 修复 | 内测报告、上线就绪 |

### 0.2 团队假设（单人 + Vibe Coding）
- **1 人**（你）
- 配合 Vibe Coding 工具（Claude Code 等），单人等效约 1.5-2.5 人
- 周产能 **5-6 人天**（含 AI 协作）
- 14 周总产能约 70-85 人天，**远低于原 7.5 人 × 14 周的 500+ 人天**
- **结论**：必须大幅瘦身，砍掉一切"非端到端主干"的能力
- 专注策略：先打通"采访 → 成稿"主链路，再补合规与体验

### 0.3 技术选型（单人版定稿）

> 与原版的差异用 🚩 标注，**主动降级/合并**部分以减小运维与开发负担。

#### 前端（保持不变）
- **桌面端**：Vue 3 + Vite + Element Plus + Pinia + Vue Router 4 + Axios
- **移动端 H5**：Vue 3 + Vite + Vant UI（断点切换，关键页面覆盖）
- 🚩 **PWA 简化为**：仅 web manifest + 基础 SW 缓存（vite-plugin-pwa 默认配置），不做 Web Push
- **构建**：Vite + pnpm
- **代码规范**：ESLint + Prettier + Husky

#### 业务后端：🚩 模块化单体（不再微服务）
- **框架**：Spring Boot 3.x
- **模块划分**（一个工程下分 package，**不**拆服务）：
  - `module-account` 账号/工作区/项目
  - `module-auth` 认证、Token、权限
  - `module-memory` 人物/采访/素材/成稿（MongoDB 主体）
  - `module-timeline` 时间线
  - `module-compliance` 授权、导出、删除、审计
  - `module-bff` 前端聚合层（避免前端跨服务拼装）
- 🚩 **不引入** Spring Cloud Gateway / Nacos / OpenFeign / Sentinel
- **数据库**：
  - MySQL 8.0（账号、工作区、项目、人物、授权、分享）
  - MongoDB 6.0（采访消息、成稿、批注、AI 结果）
  - 🚩 **不引入** Milvus（一期用 MongoDB 文本检索；RAG 二期）
- **缓存 / 队列**：
  - Redis（缓存、会话、SSE 进度、限流计数）
  - 🚩 **不引入** RabbitMQ（一期用 Redis Streams 或 FastAPI 后台任务；AI 任务走异步 HTTP）
- **安全**：Spring Security + JWT（Access + Refresh），BCrypt 密码，敏感字段 AES 加密
- **ORM**：MyBatis-Plus（MySQL）+ Spring Data MongoDB
- **API 文档**：Knife4j（Swagger）
- **CI**：GitHub Actions + Maven 多模块

#### AI 后端：🚩 单 FastAPI 应用（不再拆 5 个服务）
- **框架**：FastAPI + Uvicorn + Pydantic v2
- **路由**（单工程下分 router）：
  - `router/interview` AI 采访对话
  - `router/narrative` 叙事生成（2 模板：人物/家族）
  - `router/asset` 图像基础分析（OCR、敏感信息）
  - 🚩 **不做** multimodal、rag、media-gen、asr/tts 路由
- **模型**：主选 **DeepSeek**，备选 **Qwen**（手工切换，不做自动降级）
- **依赖管理**：uv / Poetry
- **异步**：FastAPI BackgroundTasks + Redis Streams（不引入 Celery）

#### 存储与分发（保持不变）
- **对象存储**：阿里云 OSS
- **CDN**：阿里云 CDN（公开阅读页）
- **上传**：服务端 STS 签名直传 + Referer 防盗链

#### 🚩 可观测性大幅简化
- 不引入 SkyWalking / Prometheus / Grafana / ELK
- 用 **本地文件日志**（Spring Boot logback + Python logging JSON 输出）
- 通过 `grep` / `less` / 简单 shell 脚本排查
- 二期再上完整可观测性

#### 🚩 部署大幅简化
- **阿里云 ECS 单机**（4C8G 起，按需升配）
- **Docker Compose** 启动：MySQL / MongoDB / Redis / Spring Boot / FastAPI / Nginx
- **不引入** K8s / Harbor / ArgoCD
- Nginx 负责 HTTPS、域名、限流、静态资源

### 0.4 目录结构（单人版）
```
moment-weaver/
├── frontend/                       # Vue 3 + Element Plus + Vant
│   └── web/                        # 单 SPA（响应式）
├── backend/                        # Spring Boot 模块化单体
│   ├── module-account/
│   ├── module-auth/
│   ├── module-memory/
│   ├── module-timeline/
│   ├── module-compliance/
│   ├── module-bff/
│   └── pom.xml
├── ai/                             # FastAPI 单应用
│   ├── app/
│   │   ├── routers/
│   │   ├── services/
│   │   ├── prompts/                # prompt 模板集中管理
│   │   └── main.py
│   └── pyproject.toml
├── deploy/
│   ├── docker-compose.yml          # 全部中间件 + 服务
│   ├── nginx/
│   └── env.example
├── docs/
│   ├── PRD.md
│   └── DevelopmentPlan.md
└── scripts/
    ├── seed.sql                    # 种子数据
    └── smoke-test.sh               # 冒烟测试脚本
```

### 0.5 模块边界（替代原服务拆分）

| 模块 | 主要职责 | 关键数据存储 | 对前端 API 风格 |
| --- | --- | --- | --- |
| module-account | 账号、工作区、项目 CRUD | MySQL | REST |
| module-auth | 登录、Token、权限拦截 | MySQL + Redis | REST |
| module-memory | 人物/采访/素材/成稿/批注 | MySQL + MongoDB | REST + SSE |
| module-timeline | 时间线事件、聚合 | MySQL + MongoDB | REST |
| module-compliance | 授权、导出、删除、审计、分享 | MySQL + MongoDB | REST |
| module-bff | 跨模块聚合、缓存、限流 | — | REST（前端唯一入口） |
| AI（FastAPI） | 对话、叙事、基础图像分析 | MongoDB | REST + SSE |

**调用链**：
```
浏览器 → Nginx → module-bff → 各 module（同进程 in-process 调用）
                       │
                       └→ HTTP → AI（FastAPI，异步任务 Redis Streams）
```

### 0.6 🚩 暂不实现的进阶能力
- 微服务拆分、K8s、GPU 节点
- 知识图谱、Milvus 向量库、RAG
- 图像/视频生成、Stable Diffusion
- 多模型自动降级（手动切换）
- 实时多人协同、视频采访
- Web Push、邮件通知（仅站内）
- i18n（仅 zh-CN，框架预埋）
- SkyWalking / Prometheus / ELK（仅文件日志）

### 0.7 Solo + Vibe Coding 工作模式

> 这一节是给你（单人开发）的元方法论，比具体任务更重要。

#### 核心原则
1. **Spec First, Code Second**：每个任务开始前先在 PRD/DevPlan 里写清楚"我要做什么、验收标准是什么"，再让 AI 生成。
2. **小步快跑，每步可运行**：每完成一个子任务就跑一次 e2e，宁可每天跑 10 次，不要 1 周跑 1 次。
3. **AI 强项交出去，人专注弱项**：
   - 交出去：CRUD、API stub、Vue 组件、数据库迁移、单元测试样板、Dockerfile
   - 人写：领域模型、AI prompt 模板、合规逻辑、安全策略、架构决策、关键 bug 修复
4. **AI 输出永远要 review + 跑通**：
   - 看 1 遍 diff，**禁止**不看就合
   - 必须本地起服务 + 跑通端到端
   - 关键模块（授权、删除）必须手写测试用例
5. **避免"再加个 feature"陷阱**：每加一个 feature 至少多 1-2 天，**先跑通最小链路**。

#### 每日节奏建议
| 时段 | 活动 |
| --- | --- |
| 上午 1 | 写今日 3 个子任务到 TODO（含验收标准） |
| 上午 2 | 用 AI 生成代码，本人 review + 整合 |
| 下午 1 | 启动服务、跑端到端、记笔记 |
| 下午 2 | 修 bug / 调 prompt / 写第二天任务 |
| 晚 | 收尾 + 提交 + 简短日志 |

#### Prompt 模板（自用）

**生成组件时**：
```
基于 Element Plus，生成「项目卡片」组件：
- props: project: { id, name, type, lastActiveAt, memberCount }
- 类型徽标：family=蓝，personal=绿
- 点击跳转 /projects/:id
- 移动端 <768px 时 Vant 风格简化（隐藏 memberCount 文字）
- 含 TypeScript 类型
```

**生成 API 时**：
```
基于 Spring Boot 3 + MyBatis-Plus，生成「项目」CRUD：
- 实体 Project 含 id, ownerId, workspaceId, type, name, status, createdAt
- Service / Controller / Mapper 三层
- 软删除字段 deleted
- 含统一返回体 R<T>
- 含分页查询 /api/v1/projects?page=1&size=20
```

**生成 prompt 模板时**：
```
为「家族小传」写 AI 成稿 prompt：
- 输入：{subjectName, facts: Fact[], timeline: TimelineEvent[], assets: AssetMeta[]}
- 输出：Markdown 字符串，章节顺序固定：开篇、家族渊源、关键年代、家族轶事、结语
- 字数 3000-5000
- 不允许虚构事实；事实不足时说"暂无素材"
- 必须输出每段 provenance: ai_generated | ai_rewritten
```

#### 反模式（不要这么做）
- ❌ 让 AI 一次写完整模块，自己不读
- ❌ 一边写一边改 PRD/DevPlan
- ❌ 跳过 review 直接 git commit
- ❌ 不写验收标准就让 AI 生成
- ❌ 同时启动 >3 个子任务并行（单人 context 切不动）

#### 卡点时的处理
- 同一个 bug 调试 >2h：停下，写 200 字描述贴 issue / 找朋友 / 第二天再处理
- 模型输出质量差：拆小任务、补上下文、给反例
- 失去焦点：回到 PRD Section 1.4 MVP 范围，**砍**

---

## 1. M0 · W1 立项与基线

### 1.1 任务（WBS）
- T-001 需求自审（重读 PRD Section 1.4，确认瘦版范围）
- T-002 ADR 3 份：
  - ADR-001 前端技术（Vue3/Vite/Element Plus/Vant）
  - ADR-002 后端架构（Spring Boot 3 模块化单体 + FastAPI 单应用）
  - ADR-003 数据存储分库（MySQL 8.0 + MongoDB 6.0 + Redis，全部本地原生）
- T-003 本地服务确认：MySQL / MongoDB / Redis 已装且端口可达（3306 / 27017 / 6379）
- T-004 仓库初始化（按 0.4 目录结构）
- T-005 法务三件套 V0.1：授权书、隐私政策、用户协议（可用模板自填）
- T-006 阿里云账号准备：OSS 桶 + CDN + ECS（按需）+ 短信签名（验证码用）
- T-007 设计 token：Element Plus 主题变量、Vant 主题变量、统一色板
- T-008 创建数据库 `moment_weaver`（MySQL + MongoDB）
- T-009 跑通 Spring Boot / FastAPI / Vite 三件套 Hello World 联通

### 1.2 交付物
- 自审签字版 PRD
- 3 份 ADR
- 仓库结构 + 三件套 Hello World 跑通
- 法务三件套 V0.1
- 阿里云资源清单

---

## 2. M1 · W2-W3 地基

### 2.1 任务
- T-101 Spring Boot 工程初始化（多 module Maven）
- T-102 `module-account` 骨架：账号、工作区、项目 CRUD（**team 类型预留，先不实现**）
- T-103 `module-auth`：注册/登录/找回/退出、JWT 签发与刷新、Spring Security 链
- T-104 MySQL ER 设计 + Flyway 迁移脚本（账户/工作区/项目/项目成员）
- T-105 MongoDB collection 设计（采访消息/成稿/批注）
- T-106 前端 Vite + Vue3 + Element Plus 初始化，Pinia/Vue Router/Axios 拦截器
- T-107 设计系统 v0：按钮/表单/卡片/列表（Element Plus 主题变量，Vant 同步）
- T-108 通用基础设施：日志（logback JSON）、统一错误码、Knife4j
- T-109 阿里云 OSS STS 签名服务
- T-110 FastAPI 工程初始化 + 与 Spring Boot 的联调 demo

### 2.2 关键 API 草案
```http
POST  /api/v1/auth/register          { phone, code | email, password }
POST  /api/v1/auth/login             { identifier, credential }
POST  /api/v1/auth/refresh
POST  /api/v1/auth/logout

GET   /api/v1/workspaces
POST  /api/v1/workspaces            { name }
POST  /api/v1/workspaces/:id/members{ email, role, expiresAt }

POST  /api/v1/projects              { type: family|personal, name }   # team 二期
GET   /api/v1/projects?workspaceId=...
GET   /api/v1/projects/:id
PATCH /api/v1/projects/:id
DELETE /api/v1/projects/:id

POST  /api/v1/oss/sts               # 前端取直传凭证
```

### 2.3 Gate
- "注册 → 登录 → 建项目" 端到端跑通（前后端联调）
- Knife4j 文档可访问
- FastAPI 与 Spring Boot 通过 HTTP 联调 demo 跑通

---

## 3. M2 · W4-W5 人物 / 授权 / 采访

### 3.1 任务
- T-201 `module-memory` 启动：人物（Subject）CRUD
- T-202 `module-compliance` 授权：授权书版本管理 + 一次性授权链接
- T-203 授权状态机：`pending → granted → revoked | expired`（MySQL 强事务）
- T-204 公开 H5 授权页（移动端友好）
- T-205 我的授权中心（被采访者视角）
- T-206 采访会话（InterviewSession）CRUD，消息体落 MongoDB
- T-207 AI 采访多轮对话：FastAPI `router/interview`，HTTP 异步 + SSE 推送
- T-208 实时事实抽取写入 TimelineEvent 候选
- T-209 采访总结生成
- T-210 前端：采访对话页（Element Plus + Vant 断点）

### 3.2 关键 API 草案
```http
POST  /api/v1/projects/:pid/subjects                       { displayName, relation }
GET   /api/v1/projects/:pid/subjects
POST  /api/v1/subjects/:sid/authorization/request          { projectId, scopes[], expiresAt }
GET   /api/v1/authorization/:token                         # 公开页
POST  /api/v1/authorization/:token/grant                   { consentVersion, signature }
POST  /api/v1/authorization/:token/revoke
GET   /api/v1/me/authorizations

POST  /api/v1/projects/:pid/sessions                       { subjectId, mode: text }  # 一期仅 text
GET   /api/v1/sessions/:sid/messages?cursor=...
POST  /api/v1/sessions/:sid/messages                       { content, attachments? }  # SSE 流式
POST  /api/v1/sessions/:sid/summarize
GET   /api/v1/sessions/:sid/facts
PATCH /api/v1/facts/:fid
```

### 3.3 关键数据模型（精简）
```ts
// MySQL（强事务）
Subject { id, projectId, displayName, relation?, hasAccount }
Authorization { id, subjectId, projectId, scopes: Scope[], status, token,
                consentVersion, grantedAt?, revokedAt?, expiresAt,
                ip, ua, fingerprint }

// MongoDB（文档）
Session   { _id, projectId, subjectId, mode, status, startedAt, endedAt }
Message   { _id, sessionId, role: interviewer|ai|subject, content, createdAt }
Fact      { _id, sessionId, type: time|place|person|event, payload, confidence, status }
```

### 3.4 Gate
- 法务授权流程走查通过（自检 + 朋友 review）
- AI 采访 demo：连续 10 轮不重复、不卡死
- 日志中能 grep 出"浏览器 → bff → memory → ai"的请求路径

---

## 4. M3 · W6-W7 素材 / 时间线

### 4.1 任务
- T-301 素材上传：OSS STS 签名直传 + 病毒扫描 + 敏感信息检测（身份证号/人脸，规则匹配 + 阿里云内容安全）
- T-302 图片 EXIF 时间提取（前端 EXIF 库 + 后端校验）
- T-303 🚩 音频 ASR 留接口（**一期不实现**，仅占位）
- T-304 素材库视图：时间线/人物/标签筛选（Element Plus + Vant）
- T-305 时间线事件自动聚合 + 手动编辑
- T-306 🚩 不引入 Milvus；素材元数据用 MongoDB 文本索引可检索

### 4.2 关键 API 草案
```http
POST  /api/v1/oss/sts                                   # STS 直传凭证
POST  /api/v1/assets                                    # 提交已上传的文件元数据
GET   /api/v1/assets?projectId=&subjectId=&type=&from=&to=&q=
DELETE /api/v1/assets/:id

GET   /api/v1/projects/:pid/timeline
POST  /api/v1/projects/:pid/timeline/events             { occurredAt, title, factIds?, sourceAssetId? }
PATCH /api/v1/timeline/events/:eid
DELETE /api/v1/timeline/events/:eid
```

### 4.3 Gate
- 图片上传成功率 ≥ 99%（音频/视频占位可上传但不做 ASR）
- 时间线能正确聚合 ≥ 30 条事件并支持分页

---

## 5. M4 · W8-W9 成稿 / AI 标识

### 5.1 任务
- T-401 FastAPI `router/narrative` 落地 **2 模板**（人物小传 / 家族小传）
- T-402 模板化 prompt 组装器（项目类型 + 模板 + 风格三元，集中放在 `ai/prompts/`）
- T-403 段落级 `provenance` 数据落地（MongoDB）
- T-404 编辑器集成（AI 段落底纹 + 徽标）
- T-405 改写/润色指令
- T-406 🚩 协同批注一期不实现（单人 Owner 编辑）
- T-407 输出侧内容安全审核（阿里云合规接口）
- T-408 阅读页基础视图（Vue3 + Vant 移动端友好）
- T-409 🚩 不引入 RAG；用 MongoDB 文本检索召回事实

### 5.2 关键 API 草案
```http
POST  /api/v1/projects/:pid/drafts                       { templateId, scope, inputHints? }
GET   /api/v1/drafts/:did
PATCH /api/v1/drafts/:did/sections/:sid                  { content, provenanceOverride? }
POST  /api/v1/drafts/:did/sections/:sid/rewrite          { style: formal|casual|brief|detailed }
POST  /api/v1/drafts/:did/publish                        { watermark?: boolean }
```

### 5.3 模板示例（人物小传）
| 章节 | 字数 | 标识策略 |
| --- | --- | --- |
| 开篇速写 | 150-250 | AI |
| 早年经历 | 400-800 | AI（基于事实） |
| 关键抉择 | 400-800 | AI |
| 他人评价 | 200-400 | mixed |
| 我的记忆 | 300-600 | human 优先 |
| 资料附录 | - | 系统 |

### 5.4 Gate
- 2 模板各跑通 1 个真实项目
- 编辑器与阅读页 AI 标识 100% 一致

---

## 6. M5 · W10-W11 外延能力

### 6.1 任务
- T-501 分享链接：token 生成、权限矩阵、有效期、防盗链
- T-502 阅读页：横幅「含 AI 内容」、打印/PDF 导出
- T-503 合规中心：授权、数据导出、删除
- T-504 软删除与 30 天恢复窗口（Spring `@Scheduled` 定时物理清理）
- T-505 通知中心：**仅站内通知**（邮件/Web Push 留二期）
- T-506 权限细化（按角色 + 按资源）
- T-507 🚩 PWA 简化为：vite-plugin-pwa 默认 manifest + 基础缓存
- T-508 🚩 内部运营台一期不做（V1 用 SQL 直连后台）
- T-509 测试：核心链路 e2e + 关键单元测试 ≥ 60% 覆盖
- T-510 隐私政策/用户协议最终版上线
- T-511 法务/合规自检清单

### 6.2 关键 API 草案
```http
POST  /api/v1/projects/:pid/shares                       { scope, password?, expiresAt, allowCopy, allowDownload }
GET   /api/v1/shares/:token                              # 公开
GET   /api/v1/shares/:token/preview
POST  /api/v1/me/exports                                 { scope: all|project, projectId? }
GET   /api/v1/me/exports/:id
POST  /api/v1/me/deletion-requests                       { scope, reason? }
POST  /api/v1/me/deletion-requests/:id/restore           # 30 天内

GET   /api/v1/notifications
PATCH /api/v1/notifications/:id
```

### 6.3 合规自检（节选）
- [ ] 授权书版本号与代码绑定
- [ ] 撤回授权触发的级联处理（成稿脱敏/下线）
- [ ] 导出包仅含本人可控内容
- [ ] 软删除 30 天后定时任务物理清理
- [ ] AI 标识不可被运营手动关闭
- [ ] 未成年人识别与监护人授权强制
- [ ] OSS 桶策略最小权限（前端不可列举）
- [ ] 日志中不出现手机号/身份证/token

### 6.4 Gate
- 合规自检 0 critical
- Lighthouse 性能 ≥ 75（瘦版目标）
- 部署到阿里云 ECS，DNS + HTTPS 跑通

---

## 7. M6 · W12-W14 试运行 + 修复

### 7.1 任务
- T-601 邀请 5-10 个种子用户（朋友/家人/同事），不要多
- T-602 收集反馈、Top 10 问题修复
- T-603 AI 抽检：5-10 篇成稿质量自评
- T-604 准备上线 Checklist
- T-605 应急预案：模型手动切换、Redis 重启脚本、OSS 桶监控
- T-606 README、用户帮助中心、FAQ
- T-607 关键日志埋点：成稿完成、采访活跃度、模型时延、错误率（写到本地文件 + 简易汇总脚本）
- T-608 法务最终自检（无 PIPIA，但需自查清单）

### 7.2 试运行指标
- 任务完成率：用户能在 30 分钟内完成「采访 → 人物小传」
- 撤回授权率 < 5%（单人项目容许更高）
- AI 标识认知度 ≥ 90%（自己 review 即可）

### 7.3 Gate
- 5 个种子用户跑通主链路
- 上线 Checklist 全部勾选

---

## 8. 关键风险与对策（单人版）

| 风险 | 触发 | 对策 |
| --- | --- | --- |
| AI 响应慢 | 模型降级或网络抖动 | 手动切换 DeepSeek/Qwen；超时重试 1 次 |
| 授权书版本变更 | 法务要求 | 旧授权仍有效，新任务需签新版 |
| 撤回后成稿不可用 | 内容成稿被拆分 | 段落级来源追踪（MongoDB provenance），撤回时精确脱敏 |
| 内容安全误拦 | 关键词误伤 | 维护白名单 + 申诉通道 |
| 上下文丢失 | 单人多日开发 | 每日 5 分钟写日志（"今天做了什么、卡在哪、明天做什么"） |
| 本地环境跑不起来 | 服务版本/端口不一致 | `scripts/check-env.ps1` 一键检查；README 顶部写明前置版本 |
| 阿里云资源欠费 | 忘记关停 ECS | 设置余额告警 + 闲时降配 |
| LLM API Key 泄漏 | 提交到 git | .gitignore + GitHub Secret Scanning + Key 定期轮换 |
| 单点故障 | ECS 挂掉 | 每日 OSS 增量备份 + MySQL 每日 mysqldump 到 OSS |

---

## 9. 测试策略（单人版）

- **单元测试**：核心领域逻辑 ≥ 60% 覆盖（授权状态机、prompt 组装器、数据导出/删除）
- **集成测试**：Testcontainers（MySQL、Mongo、Redis）+ 真实 OSS bucket（dev）
- **E2E**：Playwright，**只覆盖**「注册 → 授权 → 采访 → 成稿 → 分享」主链路，不追求全功能
- **手动冒烟**：每次发版前跑 `scripts/smoke-test.sh`
- **AI 评测**：5-10 题金标集，评估事实一致性、AI 标识准确率
- **安全**：自查清单（越权、上传、Token 泄漏）

> 单人不要追求覆盖率数字，**关键路径**有测试即可。

---

## 10. 部署与运维（单人版）

- **环境**：本地（Docker Compose）+ 阿里云 ECS 单机
- **CI**：GitHub Actions 跑 lint + test + build（不自动部署，**手动 SSH 拉镜像**）
- **CD**：手动 `docker compose pull && docker compose up -d`（简单可控）
- **配置**：`.env` 文件（不进 git），密钥用阿里云 KMS 或 1Password 团队版管理
- **HTTPS**：Nginx + Let's Encrypt 自动续期
- **备份**：MySQL 每日 `mysqldump` 传到 OSS；OSS 启用跨区复制
- **日志**：logback JSON + Python logging JSON 输出到 `/var/log/moment-weaver/`
- **监控**：🚩 不做（单人项目成本不匹配）；靠云厂商的 ECS 基础监控
- **告警**：云监控的 ECS / RDS 短信告警

---

## 11. 后续路径（不做一期）

- 微服务拆分 + Spring Cloud Gateway + Nacos
- 微信小程序端
- 视频采访 / ASR / TTS
- RAG + Milvus + 知识图谱
- 图像/视频生成（GPU 节点）
- 公开阅读社区
- 国际化（英文/繁体）
- Web Push + 邮件通知
- 数字遗产
- 团队项目类型 + 多角色协同
- K8s + SkyWalking + Prometheus + Grafana + ELK

---

## 12. WBS 总览（一图速览，瘦版）

| 周 | 里程碑 | 关键交付 |
| --- | --- | --- |
| W1 | M0 | PRD 自审、3 份 ADR、本地服务确认、仓库初始化、三件套 Hello World、法务三件套 V0.1 |
| W2-3 | M1 | 账号/工作区/项目骨架、Spring Boot + FastAPI 联调、OSS STS |
| W4-5 | M2 | 人物/授权/采访 |
| W6-7 | M3 | 素材（图片优先）/时间线 |
| W8-9 | M4 | 2 模板成稿/AI 标识 |
| W10-11 | M5 | 分享/合规/通知/Vant 适配/部署 ECS |
| W12-14 | M6 | 5 用户试运行/Top10 修复/上线就绪 |
