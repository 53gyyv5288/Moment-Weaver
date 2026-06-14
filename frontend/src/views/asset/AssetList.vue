<script setup lang="ts">
/**
 * 素材列表（M3）。缩略图网格 + 筛选 + 删除。
 */
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteAsset, listProjectAssets, type AssetVO } from '@/api/asset'

const props = defineProps<{ projectId: string | number; subjectId?: string | number | null }>()
const emit = defineEmits<{ (e: 'changed'): void }>()

const items = ref<AssetVO[]>([])
const loading = ref(false)
const filterKind = ref<string>('')

async function load() {
  loading.value = true
  try {
    const { data } = await listProjectAssets(props.projectId, {
      subjectId: props.subjectId ? Number(props.subjectId) : undefined,
      kind: filterKind.value || undefined,
    })
    if (data?.code === 0) items.value = data.data || []
    else ElMessage.error(data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function onDelete(a: AssetVO) {
  try {
    await ElMessageBox.confirm(`确定删除「${a.originalName || a.id}」？此操作不可恢复。`, '确认', {
      type: 'warning',
    })
  } catch { return }
  const { data } = await deleteAsset(a.id)
  if (data?.code === 0) {
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } else {
    ElMessage.error(data?.message || '删除失败')
  }
}

function formatSize(b?: number) {
  if (!b) return ''
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(1)} MB`
}

watch(() => props.subjectId, load)
watch(filterKind, load)
onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="al">
    <div class="al__bar">
      <el-radio-group v-model="filterKind" size="small">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="image">图片</el-radio-button>
        <el-radio-button value="audio">音频</el-radio-button>
      </el-radio-group>
      <span class="al__count">共 {{ items.length }} 个</span>
    </div>

    <div v-loading="loading" class="al__grid">
      <div v-for="a in items" :key="a.id" class="al__card">
        <div class="al__thumb">
          <el-image
            v-if="a.kind === 'image'"
            :src="a.url"
            :alt="a.originalName"
            :preview-src-list="items.filter(i => i.kind === 'image').map(i => i.url)"
            :initial-index="items.filter(i => i.kind === 'image').findIndex(i => i.id === a.id)"
            fit="cover"
            style="width: 100%; height: 100%"
          />
          <div v-else-if="a.kind === 'audio'" class="al__audio">
            <audio :src="a.url" controls preload="none" />
          </div>
          <div v-else class="al__unknown">📄</div>
        </div>
        <div class="al__meta">
          <div class="al__name" :title="a.originalName">{{ a.originalName || `#${a.id}` }}</div>
          <div class="al__sub">
            <span>{{ formatSize(a.sizeBytes) }}</span>
            <span v-if="a.width && a.height">{{ a.width }}×{{ a.height }}</span>
          </div>
          <div v-if="a.caption" class="al__cap">{{ a.caption }}</div>
        </div>
        <el-button class="al__del" text type="danger" size="small" @click="onDelete(a)">删除</el-button>
      </div>

      <div v-if="!loading && items.length === 0" class="al__empty">
        <p>还没有素材，去上传一个吧。</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.al { display: flex; flex-direction: column; gap: 12px; }
.al__bar { display: flex; gap: 16px; align-items: center; }
.al__count { color: #9ca3af; font-size: 12px; }
.al__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}
.al__card {
  position: relative;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.al__thumb {
  width: 100%;
  aspect-ratio: 1 / 1;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
}
.al__thumb img { width: 100%; height: 100%; object-fit: cover; }
.al__audio { padding: 12px; width: 100%; }
.al__audio audio { width: 100%; }
.al__unknown { font-size: 40px; color: #9ca3af; }
.al__meta { padding: 8px 10px; display: flex; flex-direction: column; gap: 2px; }
.al__name {
  font-size: 13px; color: #1f2937;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.al__sub { font-size: 11px; color: #9ca3af; display: flex; gap: 8px; }
.al__cap { font-size: 12px; color: #4b5563; margin-top: 2px; }
.al__del { position: absolute; top: 6px; right: 6px; background: rgba(255,255,255,0.92); }
.al__empty { grid-column: 1 / -1; text-align: center; color: #9ca3af; padding: 40px; }
</style>