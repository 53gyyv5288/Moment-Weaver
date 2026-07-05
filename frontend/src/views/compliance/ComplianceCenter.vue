<script setup lang="ts">
/**
 * 合规中心 (M5-B)。
 * 路由：/compliance
 * 三大面板：数据导出 / 删除申请 / 回收站 / 审计日志（顶部 tab 切换）
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ExportPanel from './ExportPanel.vue'
import DeletionPanel from './DeletionPanel.vue'
import RecycleBin from './RecycleBin.vue'
import AuditLog from './AuditLog.vue'

const router = useRouter()
const tab = ref<'export' | 'deletion' | 'recycle' | 'audit'>('export')

onMounted(() => {
  // 从 query 读取 tab（如 /compliance?tab=recycle）
  const t = (router.currentRoute.value.query.tab as string) || ''
  if (['export', 'deletion', 'recycle', 'audit'].includes(t)) {
    tab.value = t as any
  }
})
</script>

<template>
  <div class="cc">
    <header class="cc__head">
      <h2>合规中心</h2>
      <p class="muted">
        数据导出、删除申请、回收站与审计日志一站式管理。
        删除有 30 天宽限期，期间可随时恢复。
      </p>
    </header>

    <el-tabs v-model="tab" class="cc__tabs">
      <el-tab-pane name="export" label="数据导出">
        <ExportPanel />
      </el-tab-pane>
      <el-tab-pane name="deletion" label="删除申请">
        <DeletionPanel />
      </el-tab-pane>
      <el-tab-pane name="recycle" label="回收站">
        <RecycleBin />
      </el-tab-pane>
      <el-tab-pane name="audit" label="审计日志">
        <AuditLog />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.cc { max-width: 960px; margin: 0 auto; }
.cc__head {
  padding-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
  margin-bottom: 16px;
}
.cc__head h2 { margin: 0 0 4px; }
.muted { color: #6b7280; font-size: 13px; margin: 0; }
.cc__tabs { background: #fff; padding: 16px; border-radius: 8px; border: 1px solid #e5e7eb; }
</style>
