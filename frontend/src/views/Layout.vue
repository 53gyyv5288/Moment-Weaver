<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import NotificationBell from '@/components/NotificationBell.vue'

const auth = useAuthStore()
const notification = useNotificationStore()
const router = useRouter()
const route = useRoute()

// 顶栏只承载全局导航；项目级入口（时间线 / 成稿 / 分享）由 ProjectLayout 提供。
// 停留在项目子页时，顶栏仍高亮「我的项目」。
const activeMenu = computed(() =>
  route.path.startsWith('/projects') ? '/projects' : route.path,
)

// 面包屑：项目子页由 ProjectLayout 自带「← 我的项目 + 项目名」页头，
// 这里不再重复（meta.hideCrumb 由 projects/:id 父路由下发，子路由自动继承）。
const crumbs = computed(() => {
  if (route.meta?.hideCrumb) return []
  const title = route.meta?.title as string | undefined
  return title ? [{ title, path: route.path }] : []
})

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  await auth.logout()
  notification.stopPolling()
  router.push('/login')
}

// 登录态变化时启停轮询
onMounted(() => {
  if (auth.isLoggedIn) notification.startPolling()
})
onUnmounted(() => {
  notification.stopPolling()
})
</script>

<template>
  <el-container class="layout">
    <el-header class="layout__header">
      <div class="layout__brand" @click="router.push('/projects')">
        <span class="layout__logo">⏳</span>
        <span class="layout__title">Moment Weaver</span>
        <span class="layout__sub">时光编织者</span>
      </div>
      <el-menu
        mode="horizontal"
        :default-active="activeMenu"
        :ellipsis="false"
        router
        class="layout__menu"
      >
        <el-menu-item index="/projects">我的项目</el-menu-item>
        <el-menu-item index="/notifications">通知</el-menu-item>
        <el-menu-item index="/compliance">合规</el-menu-item>
      </el-menu>
      <div class="layout__user">
        <NotificationBell class="layout__bell" />
        <span class="layout__name">{{ auth.user?.displayName || '未登录' }}</span>
        <el-button text @click="handleLogout">退出</el-button>
      </div>
    </el-header>

    <el-main class="layout__main">
      <el-breadcrumb v-if="crumbs.length" class="layout__crumb" separator="/">
        <el-breadcrumb-item v-for="c in crumbs" :key="c.path">
          {{ c.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
      <RouterView />
    </el-main>

    <el-footer class="layout__footer">
      <span>Moment Weaver · v0.1.0 · MVP</span>
    </el-footer>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
  background: var(--mw-bg);
}
.layout__header {
  display: flex;
  align-items: center;
  background: var(--mw-surface);
  border-bottom: 1px solid var(--mw-border);
  padding: 0 28px;
  height: 60px;
}
.layout__brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-right: 40px;
  cursor: pointer;
  user-select: none;
}
.layout__logo {
  font-size: 22px;
}
.layout__title {
  font-size: 18px;
  font-weight: 600;
  color: var(--mw-text);
  letter-spacing: 0.3px;
}
.layout__sub {
  font-size: 12px;
  color: var(--mw-text-muted);
}
.layout__menu {
  flex: 1;
  border-bottom: none !important;
  background: transparent;
}
.layout__menu :deep(.el-menu-item) {
  height: 59px;
  line-height: 59px;
  font-size: 14px;
}
.layout__user {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.layout__bell { margin-right: 4px; }
.layout__name {
  color: var(--mw-text-secondary);
  font-size: 14px;
}
.layout__main {
  max-width: 1140px;
  margin: 0 auto;
  width: 100%;
  padding: 20px 16px 32px;
}
.layout__crumb {
  font-size: 12px;
  margin-bottom: 16px;
}
.layout__footer {
  text-align: center;
  color: var(--mw-text-muted);
  font-size: 12px;
  background: transparent;
}
</style>
