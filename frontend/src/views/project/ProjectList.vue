<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, View } from '@element-plus/icons-vue'
import { listProjects, deleteProject } from '@/api/project'
import type { ProjectVO } from '@/api/project'
import { formatDateTime } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const projects = ref<ProjectVO[]>([])

async function load() {
  loading.value = true
  try {
    const { data } = await listProjects({ page: 1, size: 50 })
    if (data && data.code === 0 && data.data) {
      projects.value = data.data.records
    }
  } finally {
    loading.value = false
  }
}

async function handleDelete(p: ProjectVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除项目「${p.name}」吗？30 天内可在「合规中心 → 回收站」恢复。`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  const { data } = await deleteProject(p.id)
  if (data && data.code === 0) {
    ElMessage.success('已删除')
    load()
  }
}

function typeLabel(t: string) {
  return t === 'family' ? '家族' : t === 'personal' ? '个人' : t
}

onMounted(load)
</script>

<template>
  <div class="projects">
    <div class="projects__head">
      <div>
        <h2 class="projects__title">我的项目</h2>
        <p class="projects__lead">
          每个项目是一个人或一个家族的记忆容器：添加被采访者 → 发起授权 → AI 采访 → 生成成稿。
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="router.push('/projects/new')">
        新建项目
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />

    <el-empty
      v-else-if="!projects.length"
      description="还没有项目，从新建第一个开始吧"
    >
      <el-button type="primary" :icon="Plus" @click="router.push('/projects/new')">
        新建项目
      </el-button>
    </el-empty>

    <template v-else>
      <div class="projects__count">共 {{ projects.length }} 个项目</div>
      <div class="projects__grid">
        <article
          v-for="p in projects"
          :key="p.id"
          class="mw-card projects__card"
          @click="router.push(`/projects/${p.id}`)"
        >
          <div class="projects__cardHead">
            <el-tag
              size="small"
              round
              :type="p.type === 'family' ? 'success' : 'info'"
              effect="light"
            >
              {{ typeLabel(p.type) }}
            </el-tag>
            <span class="projects__time">{{ formatDateTime(p.createdAt) }}</span>
          </div>

          <h3 class="projects__name">{{ p.name }}</h3>
          <p class="projects__desc">{{ p.description || '暂无描述' }}</p>

          <div class="projects__foot">
            <el-button size="small" text type="primary" :icon="View" @click.stop="router.push(`/projects/${p.id}`)">
              进入
            </el-button>
            <el-button size="small" text type="danger" :icon="Delete" @click.stop="handleDelete(p)">
              删除
            </el-button>
          </div>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.projects__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}
.projects__title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--mw-text);
}
.projects__lead {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--mw-text-secondary);
  line-height: 1.6;
}
.projects__count {
  font-size: 12px;
  color: var(--mw-text-muted);
  margin-bottom: 12px;
}
.projects__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.projects__card {
  padding: 18px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}
.projects__card:hover {
  border-color: var(--mw-primary);
  box-shadow: var(--mw-shadow-hover);
  transform: translateY(-2px);
}
.projects__cardHead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.projects__time {
  font-size: 12px;
  color: var(--mw-text-muted);
}
.projects__name {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--mw-text);
  line-height: 1.4;
  word-break: break-all;
}
.projects__desc {
  margin: 0;
  font-size: 13px;
  color: var(--mw-text-secondary);
  line-height: 1.6;
  min-height: 42px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.projects__foot {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  padding-top: 10px;
  border-top: 1px dashed var(--mw-border);
}
</style>
