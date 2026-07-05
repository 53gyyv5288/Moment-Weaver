<script setup lang="ts">
/**
 * 数据导出面板 (M5-B.1)。
 * - 申请导出（全量 / 项目 / 人物）
 * - 历史列表 + 状态
 * - ready 时显示下载链接
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { createExport, listExports, getExport, type ExportRequestVO } from '@/api/compliance'

const items = ref<ExportRequestVO[]>([])
const loading = ref(false)
const submitting = ref(false)
const scope = ref<'all' | 'project' | 'subject'>('all')
const scopeTargetId = ref('')

const canSubmit = computed(() => {
  if (scope.value === 'all') return true
  return !!scopeTargetId.value.trim()
})

async function load() {
  loading.value = true
  try {
    const { data } = await listExports(0, 50)
    if (data?.code === 0) items.value = data.data?.records || []
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  try {
    await ElMessageBox.confirm(
      '提交后将异步打包数据并上传到 OSS。完成后会通过通知中心告知。',
      '申请数据导出',
      { type: 'info', confirmButtonText: '提交', cancelButtonText: '取消' },
    )
  } catch { return }
  submitting.value = true
  try {
    const { data } = await createExport({
      scope: scope.value,
      scopeTargetId: scope.value === 'all' ? undefined : scopeTargetId.value,
    })
    if (data?.code === 0) {
      ElMessage.success('已提交，5 分钟内出结果')
      await load()
    } else {
      ElMessage.error(data?.message || '提交失败')
    }
  } finally {
    submitting.value = false
  }
}

async function refreshOne(e: ExportRequestVO) {
  const { data } = await getExport(e.id)
  if (data?.code === 0) {
    const idx = items.value.findIndex(i => i.id === e.id)
    if (idx >= 0) items.value[idx] = data.data!
  }
}

function statusLabel(s: string) {
  return {
    pending: '打包中',
    ready: '可下载',
    failed: '失败',
    expired: '已过期',
  }[s] || s
}
function statusType(s: string) {
  return ({ pending: 'info', ready: 'success', failed: 'danger', expired: '' } as any)[s] || ''
}
function formatTime(s?: string) {
  if (!s) return '—'
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div class="ep" v-loading="loading">
    <el-card shadow="never" class="ep__form">
      <h3>申请新导出</h3>
      <el-form label-width="100px">
        <el-form-item label="导出范围">
          <el-radio-group v-model="scope">
            <el-radio value="all">我的全部数据</el-radio>
            <el-radio value="project">单个项目</el-radio>
            <el-radio value="subject">单个人物</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="scope !== 'all'" label="目标 ID">
          <el-input v-model="scopeTargetId" placeholder="项目 / 人物 id" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="onCreate">
            提交申请
          </el-button>
        </el-form-item>
      </el-form>
      <p class="ep__hint">
        ⚠ M5 阶段仅生成 manifest 占位包；真实全量数据导出留待 M5-C 阶段。
        导出包保留 7 天后过期。
      </p>
    </el-card>

    <h3 class="ep__sub">导出历史</h3>
    <el-empty v-if="!loading && items.length === 0" description="暂无导出记录" />

    <el-table v-else :data="items" stripe>
      <el-table-column label="ID" prop="id" width="80" />
      <el-table-column label="范围" width="120">
        <template #default="{ row }">
          {{ row.scope }}
          <span v-if="row.scopeTargetId" class="ep__target">#{{ row.scopeTargetId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <template v-if="row.status === 'ready' && row.signedUrl">
            <el-button type="primary" size="small" :icon="Download" @click="window.open(row.signedUrl, '_blank')">
              下载
            </el-button>
          </template>
          <el-button v-if="row.status === 'pending'" size="small" @click="refreshOne(row)">刷新状态</el-button>
          <span v-if="row.status === 'failed'" class="ep__fail">失败：{{ row.failReason || '—' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.ep__form { margin-bottom: 24px; }
.ep__form h3 { margin: 0 0 12px; font-size: 15px; }
.ep__hint { color: #9ca3af; font-size: 12px; margin: 0; }
.ep__sub { margin: 16px 0 12px; font-size: 15px; }
.ep__target { color: #9ca3af; font-size: 12px; margin-left: 4px; }
.ep__fail { color: #ef4444; font-size: 12px; }
</style>
