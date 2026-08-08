<script setup lang="ts">
/**
 * 项目级外壳。
 * 承载项目页头（名称 / 类型 / 描述）与项目内二级导航（概览 / 时间线 / 成稿 / 分享），
 * 让顶栏只保留全局导航，避免项目级入口混进全局菜单。
 * 路由：/projects/:id 及其所有子路由。
 */
import { computed, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getProject, type ProjectVO } from '@/api/project'

const route = useRoute()
const router = useRouter()

const projectId = computed(() => route.params.id as string)
const project = ref<ProjectVO | null>(null)
const loading = ref(false)

// 子页面（如 ProjectDetail）通过 inject('project') 复用，避免重复请求
provide('project', project)

async function loadProject() {
  loading.value = true
  try {
    const { data } = await getProject(projectId.value)
    if (data && data.code === 0) project.value = data.data
  } finally {
    loading.value = false
  }
}

const tabs = computed(() => [
  { label: '概览', path: `/projects/${projectId.value}` },
  { label: '时间线', path: `/projects/${projectId.value}/timeline` },
  { label: '成稿', path: `/projects/${projectId.value}/drafts` },
  { label: '分享', path: `/projects/${projectId.value}/shares` },
])

onMounted(loadProject)
// 从一个项目直接跳到另一个项目时重新拉取
watch(projectId, loadProject)
</script>

<template>
  <div class="pl">
    <header class="pl__head" v-loading="loading && !project">
      <el-button class="pl__back" text :icon="ArrowLeft" @click="router.push('/projects')">
        我的项目
      </el-button>

      <div class="pl__title">
        <h1 class="pl__name">{{ project?.name || '项目' }}</h1>
        <el-tag v-if="project" size="small" effect="light" round>
          {{ project.type === 'family' ? '家族' : '个人' }}
        </el-tag>
      </div>

      <p v-if="project?.description" class="pl__desc">{{ project.description }}</p>

      <el-menu
        mode="horizontal"
        :default-active="route.path"
        :ellipsis="false"
        router
        class="pl__nav"
      >
        <el-menu-item v-for="t in tabs" :key="t.path" :index="t.path">
          {{ t.label }}
        </el-menu-item>
      </el-menu>
    </header>

    <div class="pl__body">
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.pl {
  max-width: 1100px;
  margin: 0 auto;
}
.pl__head {
  margin-bottom: 20px;
}
.pl__back {
  margin-left: -8px;
  color: var(--mw-text-muted);
}
.pl__title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 6px 0 0;
}
.pl__name {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--mw-text);
  letter-spacing: 0.5px;
}
.pl__desc {
  margin: 8px 0 0;
  color: var(--mw-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.pl__nav {
  margin-top: 12px;
  border-bottom: 1px solid var(--mw-border) !important;
  background: transparent;
}
.pl__nav :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
  font-size: 14px;
  padding: 0 18px;
}
.pl__nav :deep(.el-menu-item:first-child) {
  padding-left: 0;
}
.pl__body {
  min-height: 240px;
}
</style>
