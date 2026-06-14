<script setup lang="ts">
/**
 * 项目时间线（M3）。垂直时间轴，按天分组，支持按人物/类型筛选。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listTimeline, type TimelineType } from '@/api/timeline'
import { listSubjects, type SubjectVO } from '@/api/subject'
import type { TimelineItemVO } from '@/types/api'

const route = useRoute()
const router = useRouter()

const projectId = computed(() => route.params.id as string)
const items = ref<TimelineItemVO[]>([])
const loading = ref(false)
const subjects = ref<SubjectVO[]>([])
const filterType = ref<'' | TimelineType>('')
const filterSubject = ref<string | number | ''>('')

async function load() {
  loading.value = true
  try {
    const { data } = await listTimeline(projectId.value, {
      type: filterType.value || undefined,
      subjectId: filterSubject.value || undefined,
      size: 100,
    })
    if (data?.code === 0) items.value = data.data?.records || []
    else ElMessage.error(data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadSubjects() {
  const { data } = await listSubjects(projectId.value)
  if (data?.code === 0) subjects.value = data.data || []
}

type DayGroup = { key: string; label: string; items: TimelineItemVO[] }

const grouped = computed<DayGroup[]>(() => {
  const groups = new Map<string, TimelineItemVO[]>()
  for (const it of items.value) {
    const t = it.eventAt ? new Date(it.eventAt) : new Date()
    const k = `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
    if (!groups.has(k)) groups.set(k, [])
    groups.get(k)!.push(it)
  }
  return Array.from(groups.entries())
    .sort(([a], [b]) => b.localeCompare(a))
    .map(([k, list]) => ({
      key: k,
      label: formatDay(k),
      items: list,
    }))
})

function formatDay(k: string) {
  const d = new Date(k)
  const now = new Date()
  const diff = Math.floor((now.getTime() - d.getTime()) / 86400000)
  const wk = ['日', '一', '二', '三', '四', '五', '六']
  if (diff === 0) return `今天 · ${k}`
  if (diff === 1) return `昨天 · ${k}`
  if (diff < 7) return `${diff} 天前 · ${k} 周${wk[d.getDay()]}`
  return `${k} 周${wk[d.getDay()]}`
}

function formatTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function typeLabel(t?: string) {
  switch (t) {
    case 'interview_message': return '采访消息'
    case 'asset_uploaded': return '上传素材'
    case 'ai_summary': return 'AI 摘要'
    default: return '事件'
  }
}

function typeEmoji(t?: string) {
  switch (t) {
    case 'interview_message': return '💬'
    case 'asset_uploaded': return '🖼'
    case 'ai_summary': return '✨'
    default: return '•'
  }
}

function onItem(it: TimelineItemVO) {
  switch (it.type) {
    case 'ai_summary':
      // 跳到对应的采访会话摘要页
      if (it.metadata?.sessionId) {
        router.push(`/interview/${it.metadata.sessionId}/summary`)
      }
      break
    case 'interview_message':
      if (it.metadata?.sessionId) {
        router.push(`/interview/${it.metadata.sessionId}`)
      }
      break
    case 'asset_uploaded':
      // 暂在大图预览（首版仅显示预览图链接）
      if (it.metadata?.url) window.open(it.metadata.url, '_blank')
      break
  }
}

watch([filterType, filterSubject], load)
onMounted(() => { loadSubjects(); load() })
</script>

<template>
  <div class="tl">
    <header class="tl__head">
      <h2>时间线</h2>
      <p class="muted">把采访消息、上传素材、AI 摘要按时间串起来</p>
    </header>

    <div class="tl__bar">
      <el-select v-model="filterSubject" placeholder="全部人物" clearable size="default" style="width: 180px">
        <el-option
          v-for="s in subjects"
          :key="s.id"
          :label="s.displayName"
          :value="Number(s.id)"
        />
      </el-select>
      <el-radio-group v-model="filterType" size="default">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="interview_message">消息</el-radio-button>
        <el-radio-button value="asset_uploaded">素材</el-radio-button>
        <el-radio-button value="ai_summary">摘要</el-radio-button>
      </el-radio-group>
      <span class="tl__count">共 {{ items.length }} 条</span>
    </div>

    <div v-loading="loading" class="tl__body">
      <div v-if="!loading && items.length === 0" class="tl__empty">
        <p>暂无事件。开始采访或上传素材试试。</p>
      </div>

      <section v-for="g in grouped" :key="g.key" class="tl__day">
        <div class="tl__dayHead">{{ g.label }}</div>
        <div class="tl__items">
          <article
            v-for="it in g.items"
            :key="it.id"
            class="tl__item"
            @click="onItem(it)"
          >
            <span class="tl__dot">{{ typeEmoji(it.type) }}</span>
            <div class="tl__content">
              <div class="tl__meta">
                <el-tag size="small" effect="plain">{{ typeLabel(it.type) }}</el-tag>
                <span class="tl__time">{{ formatTime(it.eventAt) }}</span>
              </div>
              <div class="tl__title">{{ it.title }}</div>
              <div v-if="it.preview" class="tl__preview">{{ it.preview }}</div>
            </div>
          </article>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.tl { max-width: 880px; margin: 0 auto; }
.tl__head h2 { margin: 0 0 4px; }
.muted { color: #9ca3af; font-size: 13px; margin: 0 0 16px; }
.tl__bar {
  display: flex; gap: 12px; align-items: center; flex-wrap: wrap;
  padding: 12px; background: #fff; border-radius: 8px;
  border: 1px solid #e5e7eb; margin-bottom: 16px;
}
.tl__count { color: #9ca3af; font-size: 12px; }
.tl__body { padding: 0 4px; }
.tl__day { margin-bottom: 24px; }
.tl__dayHead {
  font-size: 12px; font-weight: 600; color: #6b7280;
  padding: 6px 0; border-bottom: 1px dashed #e5e7eb; margin-bottom: 8px;
}
.tl__items {
  display: flex; flex-direction: column; gap: 8px;
}
.tl__item {
  display: flex; gap: 12px;
  padding: 10px 12px;
  background: #fff; border: 1px solid #e5e7eb; border-radius: 6px;
  cursor: pointer; transition: all 0.15s;
}
.tl__item:hover { border-color: #2563eb; transform: translateX(2px); }
.tl__dot {
  flex-shrink: 0; width: 32px; height: 32px;
  display: flex; align-items: center; justify-content: center;
  background: #f3f4f6; border-radius: 50%; font-size: 16px;
}
.tl__content { flex: 1; min-width: 0; }
.tl__meta { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tl__time { color: #9ca3af; font-size: 11px; }
.tl__title { color: #1f2937; font-size: 14px; font-weight: 500; }
.tl__preview {
  color: #6b7280; font-size: 13px; margin-top: 4px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.tl__empty { text-align: center; color: #9ca3af; padding: 60px 0; }
</style>