# Moment Weaver · 家族功能使用说明（M10+ Family Phase 1）

> 文档版本：v1.1
> 适用代码版本：V8/V9/V10 migration 之后
> 状态：✅ 已实现

> **注**：原计划 V7/V8/V9；因 V7 已被项目里 `V7__add_deletion_request_scope_target_id.sql` 占用，本批次 migration 重新编号为 **V8 / V9 / V10**。

---

## 一、新增了什么

| 能力 | 说明 |
|---|---|
| **家族组织** | 一个家族 = 一个容器，多个成员可加入 |
| **家族管理员** | 创建者自动成为 admin；现有 gyy_5288@qq.com 被迁移脚本自动升级为家族管理员 |
| **管理员创建成员账号** | admin 可为家人创建账号（无需家人自己注册），首次登录强制改密 |
| **3 种家族角色** | admin / editor / viewer，权限差异化 |
| **项目双重归属** | 项目既可属于「个人 workspace」（个人项目），也可属于「家族」（家族项目） |
| **强制改密** | 管理员创建的账号首次登录必须改密，前端路由守卫强制跳改密页 |
| **原有流程完全保留** | 自注册、个人项目、一次性 token 授权被采访者等**全部不变** |

---

## 二、用户角色矩阵

| 操作 | 自注册用户 | 家族 admin | 家族 editor | 家族 viewer | 被采访者 |
|---|---|---|---|---|---|
| 创建个人项目 | ✅ | ✅ | ✅ | ✅ | ❌ |
| 创建家族项目 | — | ✅ | ✅ | ❌ | ❌ |
| 创建家族 | — | ✅（创建后自己变 admin） | ❌ | ❌ | ❌ |
| 创建家族成员账号 | — | ✅ | ❌ | ❌ | ❌ |
| 移除家族成员 | — | ✅ | ❌ | ❌ | ❌ |
| 修改家族名/描述 | — | ✅ | ❌ | ❌ | ❌ |
| 编辑家族下项目 | — | ✅ | ✅ | ❌ | ❌ |
| 删除家族下项目 | — | ✅ | ❌ | ❌ | ❌ |
| 删除个人项目 | ✅（仅 Owner） | ✅ | ✅ | ✅ | ❌ |
| 采访被采访者 | ✅ | ✅ | ✅ | ❌ | ❌ |
| 生成成稿 | ✅ | ✅ | ✅ | ❌ | ❌ |
| 阅读家族项目 | ✅ | ✅ | ✅ | ✅ | ❌ |
| 同意授权 | — | — | — | — | ✅（一次性 token） |

---

## 三、数据库变更（Flyway 自动执行）

| 版本 | 内容 | 是否可逆 |
|---|---|---|
| V8 | user 表加 `is_family_admin`、`must_change_password`、`created_by_user_id` 三个字段；把 `gyy_5288@qq.com` 标记为家族管理员 | ⚠️ 字段可空，回滚需手工 |
| V9 | 新建 `family`、`family_member` 两张表 | ✅ 直接 DROP 即可 |
| V10 | project 表加 `family_id` 字段；Java migration 把 gyy_5288 的现有项目挂到「我的家族」 | ⚠️ 数据迁移需手工回滚 |

> 启动时 Flyway 会自动跑这 3 个迁移。如果数据库里有大量测试数据，**强烈建议先 dump 一份再启动**。

---

## 四、API 摘要

### 4.1 家族

| 方法 | 路径 | 权限 |
|---|---|---|
| POST | `/api/v1/families` | 任意登录用户（创建者自动成为 admin） |
| GET | `/api/v1/families` | 任意登录用户（仅返回我加入的家族） |
| GET | `/api/v1/families/:id` | 家族成员 |
| PUT | `/api/v1/families/:id` | family admin |
| GET | `/api/v1/families/:id/members` | 家族成员 |
| POST | `/api/v1/families/:id/members` | family admin（创建成员账号并加入） |
| PUT | `/api/v1/families/:id/members/:uid` | family admin（改角色/重置密码） |
| DELETE | `/api/v1/families/:id/members/:uid` | family admin |
| GET | `/api/v1/families/:id/projects` | 家族成员 |

### 4.2 账号

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/auth/register` | 公开 | 自注册流程（**不变**） |
| POST | `/api/v1/auth/login` | 公开 | 登录返回 `mustChangePassword` 标记 |
| GET | `/api/v1/auth/me` | 登录 | 返回当前 user 信息（含 `isFamilyAdmin`、`mustChangePassword`） |
| POST | `/api/v1/auth/change-password` | 登录 | **新增**改密接口，校验旧密码 + 重置强制改密标记 |

### 4.3 项目（增强）

| 方法 | 路径 | 权限变化 |
|---|---|---|
| POST | `/api/v1/projects` | **新增** `familyId` 字段，可选；传则创建家族项目（要求是家族成员） |
| GET | `/api/v1/projects` | **新增**自动返回「我加入的家族」下所有项目 |
| DELETE | `/api/v1/projects/:id` | **新增**家族项目仅 family admin 可删（个人项目仍仅 Owner） |

---

## 五、端到端验证

### 5.1 自动脚本

```powershell
# 先启动后端 + 前端 + MySQL（确保 V8/V9/V10 migration 已执行）
powershell -ExecutionPolicy Bypass -File scripts/e2e-family.ps1
```

**覆盖 13 步**：
1. 管理员登录（验证 isFamilyAdmin=true）
2. 查"我的家族"（验证 V10 自动迁移生效）
3. 创建成员 张三
4. 张三首次登录（验证 mustChangePassword=true）
5. 张三改密
6. 张三创建家族项目
7. 张三查项目列表（含家族项目）
8. 创建 viewer 妈妈
9. **viewer 妈妈创建项目 → 应被 403**
10. viewer 妈妈查家族项目（应能看到，只读）
11. viewer 妈妈查成员列表
12. 管理员移除张三
13. 被移除的张三查家族 → 应被 403

### 5.2 浏览器手动演示剧本

| 步骤 | 账号 | 操作 | 预期 |
|---|---|---|---|
| 1 | gyy_5288@qq.com | 登录 | 自动进入 /projects，名字旁有「管理员」徽章 |
| 2 | gyy_5288 | 顶栏点「家族」 | 看到一张卡「我的家族」，角色=管理员 |
| 3 | gyy_5288 | 进入「我的家族」 | 看到成员列表（仅自己）+ 概览 |
| 4 | gyy_5288 | 「创建成员账号」→ 姓名=张三，手机=13900000001，密码=zhang12345678，角色=editor | 弹窗显示「成员账号已创建」+ **明文密码** + 提示「请抄送给该成员」 |
| 5 | gyy_5288 | 同样创建妈妈（viewer 角色） | OK |
| 6 | 张三 | 用 13900000001 / zhang12345678 登录 | 自动跳到 /change-password；改密后进 /projects |
| 7 | 张三 | 进入家族 | 看到「我的家族」，角色=编辑者 |
| 8 | 张三 | 在家族下创建项目「爷爷的故事」 | 成功 |
| 9 | 妈妈 | 用 viewer 账号登录、改密 | 进家族，能看到项目，但看不到「+ 创建项目」按钮 |
| 10 | 妈妈 | 试着自己创建项目（绕过 UI 直接调 API） | 被 403 拒绝 |
| 11 | gyy_5288 | 进家族成员管理，把张三改为 viewer | 张三的编辑权限消失 |
| 12 | gyy_5288 | 移除妈妈 | 妈妈下次登录不再有该家族入口 |

---

## 六、兼容性保证

| 现有功能 | 是否影响 |
|---|---|
| 自注册 `/register` | ✅ 完全不变 |
| 默认 workspace 自动创建 | ✅ 完全不变 |
| 个人项目（无 familyId） | ✅ 完全不变 |
| 一次性 token 授权被采访者 | ✅ 完全不变 |
| 现有 ProjectVO 字段 | ✅ 全保留，新增 `familyId` 可空字段 |
| 现有 UserVO 字段 | ✅ 全保留，新增 `isFamilyAdmin` / `mustChangePassword` 可空字段 |
| 现有 API 路径 | ✅ 不变 |
| 现有数据库数据 | ⚠️ V9 migration 会自动把 gyy_5288 的项目挂到「我的家族」；**强烈建议迁移前先 dump** |

---

## 七、常见问题

### Q1：我不想让现有项目自动归属到家族怎么办？

V10 migration 是**幂等**的，且只在 `gyy_5288@qq.com` 账号存在时执行。如果你不想迁移现有项目，可以：
1. **方案 A**：在 V10 之前先把 `gyy_5288@qq.com` 改名（如改为 `admin_gyy@qq.com`），这样 V10 不会匹配，family 创建由后续手动操作。
2. **方案 B**：V10 执行后，把项目 `family_id` 改回 NULL（`UPDATE project SET family_id=NULL WHERE ...`）。

### Q2：viewer 真的完全不能编辑吗？

**是的**。后端 `FamilyAccessChecker.requireEditor` 在所有写操作前都会校验。如果家族项目要支持"部分成员可编辑"的精细 ACL，那是二期的资源级 ACL，不在 V1 范围。

### Q3：管理员能给成员重置密码吗？

**可以**。PUT `/api/v1/families/:id/members/:uid` 请求体里带 `resetPassword` 字段即可。重置后该成员下次登录必须改密。

### Q4：被采访者要不要属于家族？

**不需要**。Subject（被采访者）是项目内的档案，不属于任何家族组织。老人无手机的场景下，被采访者只需要通过 `/authz/:token` 一次性链接同意授权，永远不需要家族账号。

### Q5：现有数据会丢吗？

**不会丢**。V7 给 user 表加字段默认值 0，V8 新建表，V9 给 project 加可空字段 + 把现有数据挂到 family。**任意 migration 失败均可回滚**（详见 V7/V8/V9 脚本注释）。

---

## 八、未来扩展（二期建议）

1. **家族转让**：把 admin 身份转给另一个成员
2. **多家族切换**：一个用户加入多个家族，顶栏加切换器
3. **资源级 ACL**：项目级参与人列表，只有参与人能编辑该项目
4. **家族邀请链接**：未注册的邮箱也能通过邀请链接直接加入家族
5. **家族统计**：成员活跃度、项目进度可视化
