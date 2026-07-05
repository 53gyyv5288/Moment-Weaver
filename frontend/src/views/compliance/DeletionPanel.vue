<script setup lang="ts">
/**
 * 删除申请面板 (M5-B.1)。
 * - 申请删除（项目 / 人物 / 素材 / 成稿）
 * - 列表 + 30 天宽限期倒计时
 * - 宽限期内可恢复
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listDeletions, createDeletion, restoreDeletion, type DeletionRequestVO } from '@/api/compliance'

const items = ref<DeletionRequestVO[]>([])
const loading = ref(false)
const submitting = ref(false)
const form = ref({
  scopeTargetType: 'project' as 'project' | 'subject' | 'asset' | 'draft',
  scopeTargetId: '',
})

const TYPE_LABELS: Record<string, string> = {
  project: '项目', subject: '人物', asset: '素材', draft: '成稿',
}
const STATUS_LABELS: Record<string, string> = {
  pending: '待恢复', restored: '已恢复', executed: '已执行', cancelled: '已取消',
}
const STATUS_TYPES: Record<string, '' | 'info' | 'success' | 'warning' | 'danger'> = {
  pending: 'warning', restored: 'success', executed: 'danger', cancelled: '',
}

async function load() {
  loading.value = true
  try {
    const { data } = await listDeletions(0, 50)
    if (data?.code === 0) items.value = data.data?.records || []
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!form.value.scopeTargetId.trim()) {
    ElMessage.warning('请输入目标 ID')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将删除${TYPE_LABELS[form.value.scopeTargetType]} #${form.value.scopeTargetId}。删除后有 30 天宽限期，期间可在「回收站」恢复。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch { return }
  submitting.value = true
  try {
    const { data } = await createDeletion({
      scopeTargetType: form.value.scopeTargetType,
      scopeTargetId: form.value.scopeTargetId.trim(),
    })
    if (data?.code === 0) {
      ElMessage.success('删除申请已创建')
      form.value.scopeTargetId = ''
      await load()
    } else {
      ElMessage.error(data?.message || '提交失败')
    }
  } finally {
    submitting.value = false
  }
}

async function onRestore(d: DeletionRequestVO) {
  try {
    await ElMessageBox.confirm(
      `恢复${TYPE_LABELS[d.scopeTargetType]} #${d.scopeTargetId}？恢复后该资源对所有项目成员重新可见。`,
      '恢复资源',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' },
    )
  } catch { return }
  const { data } = await restoreDeletion(d.id)
  if (data?.code === 0) {
    ElMessage.success('已恢复')
    await load()
  } else {
    ElMessage.error(data?.message || '恢复失败')
  }
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
  <div class="dp" v-loading="loading">
    <el-card shadow="never" class="dp__form">
      <h3>申请新删除</h3>
      <el-form label-width="100px" :inline="false">
        <el-form-item label="资源类型">
          <el-radio-group v-model="form.scopeTargetType">
            <el-radio value="project">项目</el-radio>
            <el-radio value="subject">人物</el-radio>
            <el-radio value="asset">素材</el-radio>
            <el-radio value="draft">成稿</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="目标 ID">
          <el-input v-model="form.scopeTargetId" placeholder="资源 id" />
        </el-form-item>
        <el-form-item>
          <el-button type="danger" :loading="submitting" @click="onCreate">
            申请删除
          </el-button>
        </el-form-item>
      </el-form>
      <p class="dp__hint">
        ⚠ 删除后保留 30 天宽限期，期间可恢复；超过 30 天后系统将物理删除。
      </p>
    </el-card>

    <h3 class="dp__sub">我的删除申请</h3>
    <el-empty v-if="!loading && items.length === 0" description="暂无删除申请" />

    <el-table v-else :data="items" stripe>
      <el-table-column label="ID" prop="id" width="80" />
      <el-table-column label="资源" width="160">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ TYPE_LABELS[row.scopeTargetType] || row.scopeTargetType }}</el-tag>
          <span class="dp__target">#{{ row.scopeTargetId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_TYPES[row.status]">{{ STATUS_LABELS[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="宽限期" width="200">
        <template #default="{ row }">
          <span v-if="row.status === 'pending'">
            剩 {{ row.daysUntilExpiry }} 天
            <span class="dp__subtime">（至 {{ formatTime(row.graceExpiresAt) }}）</span>
          </span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button v-if="row.status === 'pending'" type="primary" size="small" @click="onRestore(row)">
            恢复
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.dp__form { margin-bottom: 24px; }
.dp__form h3 { margin: 0 0 12px; font-size: 15px; }
.dp__hint { color: #f59e0b; font-size: 12px; margin: 0; background: #fffbeb; padding: 8px 12px; border-radius: 4px; }
.dp__sub { margin: 16px 0 12px; font-size: 15px; }
.dp__target { color: #9ca3af; font-size: 12px; margin-left: 6px; }
.dp__subtime { color: #9ca3af; font-size: 11px; }
</style>
