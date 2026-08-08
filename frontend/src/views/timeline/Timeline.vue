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
    case 'narrative_draft_created': return '成稿创建'
    case 'narrative_draft_section_edited': return '章节编辑'
    case 'narrative_draft_published': return '成稿发布'
    default: return '事件'
  }
}

function typeEmoji(t?: string) {
  switch (t) {
    case 'interview_message': return '💬'
    case 'asset_uploaded': return '🖼'
    case 'ai_summary': return '✨'
    case 'narrative_draft_created': return '📄'
    case 'narrative_draft_section_edited': return '✏️'
    case 'narrative_draft_published': return '✅'
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
    case 'narrative_draft_created':
    case 'narrative_draft_published':
      // 跳到对应成稿的阅读页（如果是发布）或编辑页
      if (it.metadata?.draftId) {
        const status = it.metadata?.status as string | undefined
        if (status === 'published') {
          router.push(`/drafts/${it.metadata.draftId}/read`)
        } else {
          router.push(`/drafts/${it.metadata.draftId}/edit`)
        }
      }
      break
    case 'narrative_draft_section_edited':
      if (it.metadata?.draftId) {
        router.push(`/drafts/${it.metadata.draftId}/edit`)
      }
      break
  }
}

watch([filterType, filterSubject], load)
onMounted(() => { loadSubjects(); load() })
</script>

<template>
  <div class="tl">
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
        <el-radio-button value="narrative_draft_created">成稿</el-radio-button>
        <el-radio-button value="narrative_draft_published">发布</el-radio-button>
      </el-radio-group>
      <span class="tl__count">共 {{ items.length }} 条</span>
    </div>

    <div v-loading="loading" class="tl__body">
      <div v-if="!loading && items.length === 0" class="tl__empty">
        <p>暂无事件。开始采访、上传素材或新建成稿，动态会按时间串在这里。</p>
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
.tl { max-width: 880px; }
.tl__bar {
  display: flex; gap: 12px; align-items: center; flex-wrap: wrap;
  padding: 12px; background: var(--mw-surface); border-radius: var(--mw-radius);
  border: 1px solid var(--mw-border); margin-bottom: 16px;
}
.tl__count { color: var(--mw-text-muted); font-size: 12px; margin-left: auto; }
.tl__body { padding: 0 4px; }
.tl__day { margin-bottom: 24px; }
.tl__dayHead {
  font-size: 12px; font-weight: 600; color: var(--mw-text-secondary);
  padding: 6px 0; border-bottom: 1px dashed var(--mw-border); margin-bottom: 8px;
}
.tl__items {
  display: flex; flex-direction: column; gap: 8px;
}
.tl__item {
  display: flex; gap: 12px;
  padding: 10px 12px;
  background: var(--mw-surface); border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius-sm);
  cursor: pointer; transition: all 0.15s;
}
.tl__item:hover { border-color: var(--mw-primary); transform: translateX(2px); }
.tl__dot {
  flex-shrink: 0; width: 32px; height: 32px;
  display: flex; align-items: center; justify-content: center;
  background: var(--mw-cream); border-radius: 50%; font-size: 16px;
}
.tl__content { flex: 1; min-width: 0; }
.tl__meta { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tl__time { color: var(--mw-text-muted); font-size: 11px; }
.tl__title { color: var(--mw-text); font-size: 14px; font-weight: 500; }
.tl__preview {
  color: var(--mw-text-secondary); font-size: 13px; margin-top: 4px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.tl__empty { text-align: center; color: var(--mw-text-muted); padding: 60px 0; }
</style>