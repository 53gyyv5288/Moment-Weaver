<script setup lang="ts">
/**
 * 回收站 (M5-B.2)。
 * - 列出被软删的 project / subject（30 天内）
 * - 显示距永久删除还剩几天
 * - 一键恢复
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listRecycleBin, restoreFromRecycleBin, type RecycleBinItemVO } from '@/api/compliance'

const items = ref<RecycleBinItemVO[]>([])
const loading = ref(false)
const type = ref<'' | 'project' | 'subject'>('')

const TYPE_LABELS: Record<string, string> = {
  project: '项目', subject: '人物', asset: '素材', draft: '成稿',
}

async function load() {
  loading.value = true
  try {
    const { data } = await listRecycleBin(type.value || undefined)
    if (data?.code === 0) items.value = data.data || []
  } finally {
    loading.value = false
  }
}

async function onRestore(item: RecycleBinItemVO) {
  try {
    await ElMessageBox.confirm(
      `确认恢复「${item.title}」？恢复后该资源对所有项目成员重新可见。`,
      '恢复资源',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' },
    )
  } catch { return }
  const { data } = await restoreFromRecycleBin(item.type, item.id)
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
  <div class="rb" v-loading="loading">
    <el-radio-group v-model="type" class="rb__filter" @change="load">
      <el-radio-button value="">全部</el-radio-button>
      <el-radio-button value="project">项目</el-radio-button>
      <el-radio-button value="subject">人物</el-radio-button>
    </el-radio-group>

    <el-empty v-if="!loading && items.length === 0" description="回收站是空的" />

    <el-card
      v-for="item in items"
      :key="`${item.type}_${item.id}`"
      shadow="never"
      class="rb__item"
    >
      <div class="rb__row">
        <div class="rb__info">
          <el-tag size="small" effect="plain" class="rb__type">
            {{ TYPE_LABELS[item.type] || item.type }}
          </el-tag>
          <span class="rb__title">{{ item.title }}</span>
          <span class="rb__id">#{{ item.id }}</span>
        </div>
        <div class="rb__meta">
          <span class="rb__days">
            <el-tag v-if="item.daysUntilPermanentDelete <= 3" type="danger" size="small">
              剩 {{ item.daysUntilPermanentDelete }} 天
            </el-tag>
            <el-tag v-else type="warning" size="small">
              剩 {{ item.daysUntilPermanentDelete }} 天
            </el-tag>
          </span>
          <span class="rb__deletedAt">删除于 {{ formatTime(item.deletedAt) }}</span>
          <el-button type="primary" size="small" @click="onRestore(item)">恢复</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.rb__filter { margin-bottom: 16px; }
.rb__item { margin-bottom: 8px; }
.rb__row {
  display: flex; align-items: center; gap: 12px;
  flex-wrap: wrap;
}
.rb__info { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.rb__type { flex-shrink: 0; }
.rb__title { font-weight: 500; color: #1f2937; }
.rb__id { color: #9ca3af; font-size: 12px; }
.rb__meta { display: flex; align-items: center; gap: 12px; }
.rb__days { font-weight: 500; }
.rb__deletedAt { color: #9ca3af; font-size: 12px; }
</style>
