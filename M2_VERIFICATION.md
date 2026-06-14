# M2 Gate 验证手册

> 适用版本：v0.2.0
> 覆盖：人物 / 授权 / AI 采访 SSE 流式

---

## 0. 准备

### 0.1 启动依赖

```powershell
# 终端 A：MongoDB
mongod --dbpath D:\mongo-data  # 或用 Windows 服务

# 终端 B：MySQL（已运行）
# 端口 3306，admin/1234，库 moment_weaver

# 终端 C：FastAPI
cd "C:\Learning\Moment Weaver\ai"
copy .env.example .env
# 编辑 .env，把 LLM_BASE_URL/LLM_API_KEY/LLM_MODEL 指向你的 MiniMax 2.7
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e .
uvicorn app.main:app --reload --port 8000

# 终端 D：Spring Boot
cd "C:\Learning\Moment Weaver\backend"
mvn -pl moment-weaver-app -am spring-boot:run

# 终端 E：前端
cd "C:\Learning\Moment Weaver\frontend"
npm install
npm run dev
```

### 0.2 验证基础

- `curl http://localhost:8000/healthz` → `{"status":"ok"}`
- `curl http://localhost:8080/api/v1/healthz` → 200
- `curl http://localhost:5173` → HTML
- `curl http://localhost:8000/api/v1/interview/stream` ← 不要 GET；该端点只接受 POST

### 0.3 准备 LLM

最简路径：本地起一个 Ollama：
```powershell
# 安装 Ollama 后
ollama pull qwen2.5:7b
# 服务默认监听 http://localhost:11434
```

然后 ai/.env 应该是：
```
LLM_BASE_URL=http://localhost:11434/v1
LLM_API_KEY=ollama
LLM_MODEL=qwen2.5:7b
```

---

## 1. 注册 + 登录

```bash
REG=$(curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"account":"13800000001","password":"password123","displayName":"测试用户"}')
echo $REG
TOKEN=$(echo $REG | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo TOKEN=$TOKEN
```

如果返回 `USER_ALREADY_EXISTS`，用 `POST /api/v1/auth/login` 取 token。

---

## 2. 创建一个项目

```bash
PRJ=$(curl -s -X POST http://localhost:8080/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type":"family","name":"爸爸的知青岁月","description":"M2 测试"}')
echo $PRJ
PROJECT_ID=$(echo $PRJ | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
echo PROJECT_ID=$PROJECT_ID
```

---

## 3. 添加一个被采访者

```bash
SUBJ=$(curl -s -X POST http://localhost:8080/api/v1/projects/$PROJECT_ID/subjects \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"displayName":"父亲","relation":"爸爸","note":"1948年生，安徽人"}')
echo $SUBJ
SUBJECT_ID=$(echo $SUBJ | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
echo SUBJECT_ID=$SUBJECT_ID
```

---

## 4. 发起授权

```bash
AUTHZ=$(curl -s -X POST http://localhost:8080/api/v1/projects/$PROJECT_ID/authorizations \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"subjectId\":$SUBJECT_ID,\"scopes\":[\"interview\",\"narrative\"]}")
echo $AUTHZ
TOKEN=$(echo $AUTHZ | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
echo AUTHZ_TOKEN=$TOKEN
PUBLIC_URL=$(echo $AUTHZ | python -c "import sys,json;print(json.load(sys.stdin)['data']['publicUrl'])")
echo PUBLIC_URL=$PUBLIC_URL
```

记录 `PUBLIC_URL`，下一步在浏览器中打开。

---

## 5. 公开授权（浏览器）

把 `PUBLIC_URL` 粘贴到浏览器，应看到：
- 「Moment Weaver · 知情同意书」标题
- 上方卡片显示项目、状态、同意书版本、过期时间
- 中间渲染的 Markdown 同意书正文
- 底部勾选框 + 「拒绝 / 我已阅读并同意」按钮

✅ 勾选后点「我已阅读并同意」
- 应弹出成功提示
- 状态变成「已同意」

---

## 6. 启动采访会话

回到 API：

```bash
SESS=$(curl -s -X POST http://localhost:8080/api/v1/interview/sessions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"projectId\":$PROJECT_ID,\"subjectId\":$SUBJECT_ID}")
echo $SESS
SESSION_ID=$(echo $SESS | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
echo SESSION_ID=$SESSION_ID
```

如果返回 `AUTHORIZATION_INVALID`（3006），回第 5 步确保已勾选同意。

---

## 7. SSE 流式发送

```bash
curl -N -X POST http://localhost:8080/api/v1/interview/sessions/$SESSION_ID/message \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"content\":\"您是哪一年出生的？\"}"
```

预期输出（SSE 格式）：
```
event:start
data:{}

event:token
data:我

event:token
data:是

event:token
data:一

...

event:done
data:{}
```

如果一直不返回：
- 看 FastAPI 日志有没有「LLM 502」/「Connection refused」
- 看 Spring Boot 日志有没有「AI upstream」异常
- 看 Ollama/你的 LLM 服务有没有跑

---

## 8. 浏览器端到端

访问 http://localhost:5173 → 登录 → 列表 → 进入项目 → 选人物 → 点「采访」

- 应进入 /interview/... 房间
- 输入框打一句话，AI 逐 token 输出
- 流结束后，刷新页面再开会话，Q&A 应保留（来自 MongoDB）

---

## 9. 常见坑

| 现象 | 原因 | 修法 |
|---|---|---|
| 502 from FastAPI LLM | LLM_BASE_URL 配错 | `curl $LLM_BASE_URL/models` 验证 |
| SSE 流到一半断了 | Vite 代理缓冲 | `vite.config.ts` 已在 `/api` 代理，需重启 dev |
| `AUTHORIZATION_INVALID` | 没勾选同意书 | 浏览器重开 PUBLIC_URL 重走第 5 步 |
| MongoDB 报错 | 未启动 | `mongod --dbpath D:\mongo-data` |
| Flyway checksum 失败 | 旧库还在 | `DROP DATABASE moment_weaver;` 后重跑 |
| Spring SseEmitter 超时 | 单次 > 3 分钟 | `InterviewController` 把 180000L 调大，或前端用 abort 重连 |
| `WebClientResponseException 401` | 调 FastAPI 鉴权失败 | 你的 LLM 服务可能要 token，env 加 `LLM_API_KEY=sk-xxx` |

---

## 10. Gate 通过标准

- ✅ Step 5 浏览器同意书正确渲染
- ✅ Step 7 SSE 收到 token 级别输出
- ✅ Step 8 前端对话流畅，刷新后历史保留
- ✅ MongoDB 中能看到 `interview_session` 文档

完成即可进入 **M3：素材上传（OSS STS 真实接入）+ 时间线**。
