<script setup lang="ts">
/**
 * 审计日志 (M5-B.1)。
 * 列出当前用户的所有关键动作（登录、分享、删除、撤销、导出等）。
 */
import { onMounted, ref } from 'vue'
import { listAuditLog, AUDIT_ACTION_LABELS, type AuditLogVO } from '@/api/compliance'

const items = ref<AuditLogVO[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const { data } = await listAuditLog(0, 100)
    if (data?.code === 0) items.value = data.data?.records || []
  } finally {
    loading.value = false
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
  <div class="al" v-loading="loading">
    <el-empty v-if="!loading && items.length === 0" description="暂无审计日志" />

    <el-table v-else :data="items" stripe>
      <el-table-column label="时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="动作" width="140">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">
            {{ AUDIT_ACTION_LABELS[row.action] || row.action }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="资源">
        <template #default="{ row }">
          <span v-if="row.targetType">{{ row.targetType }} #{{ row.targetId || '—' }}</span>
          <span v-else class="al__empty">—</span>
        </template>
      </el-table-column>
      <el-table-column label="IP" width="140">
        <template #default="{ row }">
          <span class="al__ip">{{ row.ip || '—' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.al__empty, .al__ip { color: #9ca3af; }
</style>
