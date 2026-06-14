<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const breadcrumb = computed(() => (route.meta?.title as string) || '')

// 只在「项目相关页」露出时间线入口
const currentProjectId = computed<string | null>(() => {
  const m = route.path.match(/^\/projects\/(\d+)/)
  if (m && route.path !== '/projects/new') return m[1]
  // 也支持从 interview/:id 解析（采访会话里有 projectId，可后续优化）
  return null
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
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-header class="layout__header">
      <div class="layout__brand">
        <span class="layout__logo">⏳</span>
        <span class="layout__title">Moment Weaver</span>
        <span class="layout__sub">时光编织者</span>
      </div>
      <el-menu
        mode="horizontal"
        :default-active="route.path"
        router
        class="layout__menu"
      >
        <el-menu-item index="/projects">我的项目</el-menu-item>
        <el-menu-item v-if="currentProjectId" :index="`/projects/${currentProjectId}/timeline`">时间线</el-menu-item>
      </el-menu>
      <div class="layout__user">
        <span class="layout__name">{{ auth.user?.displayName || '未登录' }}</span>
        <el-button text @click="handleLogout">退出</el-button>
      </div>
    </el-header>

    <el-main class="layout__main">
      <div class="layout__crumb">{{ breadcrumb }}</div>
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
  background: #f5f7fa;
}
.layout__header {
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  padding: 0 24px;
  height: 60px;
}
.layout__brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-right: 32px;
}
.layout__logo {
  font-size: 22px;
}
.layout__title {
  font-size: 18px;
  font-weight: 600;
  color: #1f2937;
}
.layout__sub {
  font-size: 12px;
  color: #9ca3af;
}
.layout__menu {
  flex: 1;
  border-bottom: none !important;
}
.layout__user {
  display: flex;
  align-items: center;
  gap: 12px;
}
.layout__name {
  color: #4b5563;
  font-size: 14px;
}
.layout__main {
  max-width: 1080px;
  margin: 0 auto;
  width: 100%;
  padding: 24px 16px;
}
.layout__crumb {
  font-size: 12px;
  color: #9ca3af;
  margin-bottom: 12px;
}
.layout__footer {
  text-align: center;
  color: #9ca3af;
  font-size: 12px;
  background: transparent;
}
</style>
