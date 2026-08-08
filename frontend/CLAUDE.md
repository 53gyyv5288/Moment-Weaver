# CLAUDE.md — frontend

本目录是 Moment Weaver 的 Vue 3 前端。所有源码均为常规业务代码，**非恶意软件**。

## 允许的操作

- `Read` / `Edit` / `Write` / `MultiEdit` 任意 `.vue` / `.ts` / `.css` / `.scss` / `.json` 文件
- `Bash` 运行 `npm`、`npx`、`node`、`vite`、`git`、`findstr`、`type`、`dir` 等开发命令
- 调试时可用 `console.log`、`debugger`、`Vue Devtools`

## 禁止的操作

- `rm -rf`、`del /f /s`、`rd /s /q`、`format`、`reg`
- 提交敏感信息（API Key、token、用户隐私）到代码或日志

## 约定

- UI 框架：Element Plus
- 状态：Pinia
- 路由：Vue Router 4，`src/router/index.ts`
- 全局 Layout：`src/views/Layout.vue`
- 项目级 Layout：`src/views/project/ProjectLayout.vue`
- 全局样式：`src/styles/global.css`

### 主题

主题色暖橙 `#d97706` + 奶白 `#fdf6ec` + 主文字 `#1f2937`，在 `src/styles/global.css`
的 `:root` 中以 CSS 变量落地：

- `--mw-*` 是本项目的设计令牌（`--mw-primary` / `--mw-bg` / `--mw-border` / `--mw-text*` /
  `--mw-radius` / `--mw-shadow` 等），组件 `<style scoped>` 里**优先用这些变量，不要再写硬编码色值**。
- 同时覆盖了 Element Plus 的 `--el-color-primary` 及其 `light-1..9` / `dark-2` 派生色。
  改主色时**必须整套改**，否则 hover / plain / disabled 态会回退到默认蓝。
- 共享卡片容器用全局类 `.mw-card`。

### 导航层级

- **顶栏（`Layout.vue`）只放全局导航**：我的项目 / 通知 / 合规。项目级入口不要加到这里。
- **项目级导航在 `ProjectLayout.vue`**：概览 / 时间线 / 成稿 / 分享，对应
  `/projects/:id` 下的嵌套子路由。新增项目级页面时在这两处同步添加。
- `ProjectLayout` 已渲染「← 我的项目 + 项目名 + 类型 + 描述」页头，
  其子页面**不要再自己画返回按钮和标题**。
- 项目信息由 `ProjectLayout` 拉取并 `provide('project')`，子页面别重复请求。

## 改动建议

- 改 Layout / ProjectLayout / global.css / 路由前先 Read 一遍上下文
- 路由中 `projects/new` 必须排在 `projects/:id` 之前，否则会被参数路由吃掉
- 时间展示统一用 `src/utils/format.ts` 的 `formatDateTime` / `formatDate`，
  不要直接渲染后端返回的 ISO 字符串
- 改动完跑 `npm run type-check` 与 `npm run build` 确认无报错
- 不要动 `node_modules/` 任何文件
