<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listProjects, deleteProject } from '@/api/project'
import type { ProjectVO } from '@/api/project'

const router = useRouter()
const loading = ref(false)
const projects = ref<ProjectVO[]>([])
const total = ref(0)

async function load() {
  loading.value = true
  try {
    const { data } = await listProjects({ page: 1, size: 50 })
    if (data && data.code === 0 && data.data) {
      projects.value = data.data.records
      total.value = data.data.total
    }
  } finally {
    loading.value = false
  }
}

async function handleDelete(p: ProjectVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除项目「${p.name}」吗？30 天内可在「删除申请」恢复。`,
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
      <h2>我的项目 <span class="projects__count">({{ total }})</span></h2>
      <el-button type="primary" @click="router.push('/projects/new')">
        + 新建项目
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />

    <el-empty v-else-if="!projects.length" description="还没有项目，去新建第一个吧">
      <el-button type="primary" @click="router.push('/projects/new')">新建项目</el-button>
    </el-empty>

    <el-table v-else :data="projects" stripe @row-click="(row) => router.push(`/projects/${row.id}`)">
      <el-table-column prop="name" label="项目名" min-width="200">
        <template #default="{ row }">
          <strong>{{ row.name }}</strong>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.type === 'family' ? 'success' : 'info'" size="small">
            {{ typeLabel(row.type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ row.createdAt }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="right">
        <template #default="{ row }">
          <el-button text type="primary" @click.stop="router.push(`/projects/${row.id}`)">查看</el-button>
          <el-button text type="danger" @click.stop="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert
      class="projects__hint"
      type="success"
      :closable="false"
      show-icon
    >
      <template #title>M2 已实装：人物 + 授权 + AI 采访</template>
      点击项目名进入「项目详情」：添加被采访者 → 发起授权链接 → 老人打开链接同意 → 进入采访房间与 AI 采访官对话。
    </el-alert>
  </div>
</template>

<style scoped>
.projects {
  background: #fff;
  padding: 20px 24px;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}
.projects__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.projects__head h2 {
  margin: 0;
  font-size: 18px;
}
.projects__count {
  font-size: 13px;
  color: #9ca3af;
  font-weight: normal;
}
.projects__hint {
  margin-top: 16px;
}
</style>
