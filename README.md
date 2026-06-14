# Moment Weaver · 时光编织者

> AI 引导式故事采集与生成平台 · 瘦版 MVP（单人 + Vibe Coding）

## 项目结构

```
moment-weaver/
├── backend/                # Spring Boot 3 模块化单体（JDK 17）
├── ai/                     # FastAPI 单应用（Python 3.11）
├── frontend/               # Vue 3 + Vite + Element Plus + Vant
├── db/init/                # MySQL 初始 schema
├── deploy/                 # 生产部署（docker-compose，**仅 ECS 使用**）
├── docs/                   # PRD + DevelopmentPlan
└── scripts/                # 环境检查 + 启动脚本
```

## 本地开发环境前置

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 17（与 JDK 8 共存，IDE 切到 17） | 后端编译运行 |
| Maven | 3.9.9 | 后端构建 |
| Node.js | 20 | 前端 Vite |
| pnpm | latest | 前端包管理（推荐） |
| Python | 3.11 | AI 服务 |
| MySQL | 8.0 | 业务数据（admin/1234，仅本地） |
| MongoDB | 6.0 | 文档与 AI 结果 |
| Redis | 6+ | 缓存与 SSE 进度 |

> ⚠️ **安全警告**：`admin/1234` 仅用于本地 MySQL，**严禁**入 git。

## 启动顺序

### 第 1 步：环境检查
```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-env.ps1
```

### 第 2 步：建库
```powershell
# 登录 MySQL
mysql -u admin -p1234

# 跑建库脚本
source db/init/01-schema.sql
```

### 第 3 步：启动后端（新窗口）
```powershell
cd backend
mvn -pl moment-weaver-app -am spring-boot:run
```
- 健康检查：http://localhost:8080/api/v1/healthz
- 依赖自检：http://localhost:8080/api/v1/readyz
- API 文档：http://localhost:8080/swagger-ui.html

### 第 4 步：启动 AI（新窗口）
```powershell
cd ai
pip install -e .
copy .env.example .env       # 填入 DEEPSEEK_API_KEY 等
uvicorn app.main:app --reload --port 8000
```
- 健康检查：http://localhost:8000/healthz
- API 文档：http://localhost:8000/docs

### 第 5 步：启动前端（新窗口）
```powershell
cd frontend
pnpm install
pnpm dev
```
- 访问：http://localhost:5173/
- 三件套自检页：http://localhost:5173/health-check

## 一键启动（推荐）

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-all.ps1
```

## 跑通后第一件事

打开 http://localhost:5173/health-check ，**三件套状态全绿** = M0 完成 ✅

## 关键文档

- [PRD](docs/PRD.md) · 产品需求文档
- [DevelopmentPlan](docs/DevelopmentPlan.md) · 开发计划与里程碑

## 当前阶段

**M0 · 立项与基线（W1）**
- [x] PRD + DevelopmentPlan
- [x] 仓库骨架（三件套 Hello World）
- [x] MySQL 库 `moment_weaver` 建表
- [ ] 3 份 ADR（前端 / 后端 / 数据存储）
- [ ] 法务三件套 V0.1
- [ ] 阿里云 OSS / CDN 准备
