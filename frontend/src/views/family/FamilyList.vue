<script setup lang="ts">
/**
 * 我的家族列表（M10+ Family Phase 1）。
 * 路由：/families
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { listFamilies, type FamilyVO } from '@/api/family'

const router = useRouter()
const families = ref<FamilyVO[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const { data } = await listFamilies()
    if (data?.code === 0) families.value = data.data || []
    else ElMessage.error(data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function roleLabel(r: string) {
  return { admin: '管理员', editor: '编辑者', viewer: '旁观者' }[r] || r
}
function roleType(r: string) {
  return { admin: 'warning', editor: '', viewer: 'info' }[r] || ''
}

onMounted(load)
</script>

<template>
  <div class="fl" v-loading="loading">
    <header class="fl__head">
      <div>
        <h2>我的家族</h2>
        <p class="muted">家族是多人协作的容器，家族下可创建共享项目</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="router.push('/families/new')">
        创建家族
      </el-button>
    </header>

    <el-empty v-if="!loading && families.length === 0" description="还没加入任何家族">
      <el-button type="primary" @click="router.push('/families/new')">创建第一个家族</el-button>
    </el-empty>

    <div v-else class="fl__grid">
      <el-card
        v-for="f in families"
        :key="f.id"
        shadow="hover"
        class="fl__card"
        @click="router.push(`/families/${f.id}`)"
      >
        <header class="fl__cardHead">
          <h3>{{ f.name }}</h3>
          <el-tag :type="roleType(f.myRole) as any" size="small" effect="plain">
            {{ roleLabel(f.myRole) }}
          </el-tag>
        </header>
        <p v-if="f.description" class="fl__desc">{{ f.description }}</p>
        <p v-else class="muted">（暂无描述）</p>
        <footer class="fl__meta">
          <span>成员 {{ f.memberCount ?? '?' }} 人</span>
          <span>项目 {{ f.projectCount ?? '?' }} 个</span>
        </footer>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.fl { width: 100%; }
.fl__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}
.fl__head h2 { margin: 0 0 4px; }
.muted { color: #9ca3af; font-size: 13px; margin: 0; }
.fl__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.fl__card {
  cursor: pointer;
  transition: transform 0.15s;
}
.fl__card:hover { transform: translateY(-2px); }
.fl__cardHead {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.fl__cardHead h3 { margin: 0; font-size: 17px; }
.fl__desc {
  color: #6b7280;
  font-size: 13px;
  margin: 0 0 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.fl__meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #9ca3af;
  border-top: 1px solid #f3f4f6;
  padding-top: 8px;
}
</style>
