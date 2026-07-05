<script setup lang="ts">
/**
 * 成稿列表 (M4)。展示当前项目下所有 draft，按 scope / 状态筛选。
 * 顶部 "+" 按钮 → TemplatePicker。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Document, View, EditPen, MagicStick, Share } from '@element-plus/icons-vue'
import { listDrafts } from '@/api/draft'
import { listSubjects } from '@/api/subject'
import type { NarrativeDraftVO, SubjectVO } from '@/types/api'
import TemplatePicker from './TemplatePicker.vue'

const route = useRoute()
const router = useRouter()

const projectId = computed(() => route.params.id as string)
const drafts = ref<NarrativeDraftVO[]>([])
const subjects = ref<SubjectVO[]>([])
const loading = ref(false)
const filterScope = ref<'' | 'person' | 'family'>('')
const filterStatus = ref<'' | 'pending' | 'draft' | 'published' | 'archived'>('')
const showPicker = ref(false)

const TEMPLATE_LABELS: Record<string, string> = {
  'person-template-v1': '人物小传',
  'family-template-v1': '家族小传',
}

const TEMPLATE_EMOJI: Record<string, string> = {
  'person-template-v1': '👤',
  'family-template-v1': '🏛️',
}

const STATUS_LABELS: Record<string, string> = {
  pending: '待生成',
  draft: '草稿',
  published: '已发布',
  archived: '已归档',
}
const STATUS_TYPES: Record<string, '' | 'info' | 'success' | 'warning'> = {
  pending: 'warning',
  draft: 'info',
  published: 'success',
  archived: '',
}

const SCOPE_LABELS: Record<string, string> = {
  person: '人物',
  family: '家族',
}

async function load() {
  loading.value = true
  try {
    const { data } = await listDrafts(projectId.value, {
      scope: filterScope.value || undefined,
      status: filterStatus.value || undefined,
      size: 100,
    })
    if (data?.code === 0) drafts.value = data.data || []
    else ElMessage.error(data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadSubjects() {
  const { data } = await listSubjects(projectId.value)
  if (data?.code === 0) subjects.value = data.data || []
}

function subjectNames(d: NarrativeDraftVO): string {
  if (d.subjectDisplayNames && d.subjectDisplayNames.length) {
    return d.subjectDisplayNames.join('、')
  }
  return d.subjectIds
    .map((sid) => subjects.value.find(s => String(s.id) === String(sid))?.displayName || `#${sid}`)
    .join('、')
}

function onCreated(draftId: string) {
  // 创建后直接跳到编辑页
  router.push(`/drafts/${draftId}/edit`)
}

function onOpen(d: NarrativeDraftVO) {
  if (d.status === 'published') {
    router.push(`/drafts/${d.id}/read`)
  } else {
    router.push(`/drafts/${d.id}/edit`)
  }
}

async function onGenerate(d: NarrativeDraftVO) {
  await ElMessageBox.confirm(
    '将基于当前可用事实（采访 / 素材 / 备注）调用 AI 整篇生成，可能需要 30 秒左右。',
    'AI 整篇生成',
    { type: 'info' },
  ).catch(() => null)
  if (!d) return
  loading.value = true
  try {
    const { generateDraft } = await import('@/api/draft')
    const { data } = await generateDraft(d.id)
    if (data?.code === 0) {
      ElMessage.success('生成完成')
      router.push(`/drafts/${d.id}/edit`)
    } else {
      ElMessage.error(data?.message || '生成失败')
    }
  } finally {
    loading.value = false
  }
}

function onGenerateClick(d: NarrativeDraftVO, e: Event) {
  e.stopPropagation()
  onGenerate(d)
}

function formatTime(s?: string) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => { loadSubjects(); load() })
</script>

<template>
  <div class="dl" v-loading="loading">
    <header class="dl__head">
      <el-button text @click="router.push(`/projects/${projectId}`)">← 返回项目详情</el-button>
      <div class="dl__title">
        <h2>成稿</h2>
        <p class="muted">基于采访消息、素材和备注，由 AI 生成结构化叙事</p>
      </div>
      <el-button :icon="Share" plain @click="router.push(`/projects/${projectId}/shares`)">分享管理</el-button>
      <el-button type="primary" :icon="Plus" @click="showPicker = true">新建成稿</el-button>
    </header>

    <div class="dl__bar">
      <el-radio-group v-model="filterScope" size="default" @change="load">
        <el-radio-button value="">全部范围</el-radio-button>
        <el-radio-button value="person">人物</el-radio-button>
        <el-radio-button value="family">家族</el-radio-button>
      </el-radio-group>
      <el-radio-group v-model="filterStatus" size="default" @change="load">
        <el-radio-button value="">全部状态</el-radio-button>
        <el-radio-button value="pending">待生成</el-radio-button>
        <el-radio-button value="draft">草稿</el-radio-button>
        <el-radio-button value="published">已发布</el-radio-button>
      </el-radio-group>
      <span class="dl__count">共 {{ drafts.length }} 篇</span>
    </div>

    <el-empty v-if="!loading && drafts.length === 0" description="还没有成稿，点击右上角「新建成稿」开始" />

    <div v-else class="dl__grid">
      <article
        v-for="d in drafts"
        :key="d.id"
        class="dl__card"
        @click="onOpen(d)"
      >
        <div class="dl__cardHead">
          <span class="dl__emoji">{{ TEMPLATE_EMOJI[d.templateId] || '📄' }}</span>
          <el-tag size="small" effect="plain">{{ SCOPE_LABELS[d.scope] || d.scope }}</el-tag>
          <el-tag size="small" :type="STATUS_TYPES[d.status]">{{ STATUS_LABELS[d.status] || d.status }}</el-tag>
        </div>
        <h3 class="dl__cardTitle">{{ d.title || '（未命名）' }}</h3>
        <div class="dl__cardMeta">
          <span class="dl__tplName">{{ TEMPLATE_LABELS[d.templateId] || d.templateId }}</span>
          <span class="dl__sep">·</span>
          <span class="dl__subjects">{{ subjectNames(d) }}</span>
        </div>
        <div class="dl__cardMeta dl__cardMeta--sub">
          <span>{{ d.sections.length }} 章</span>
          <span v-if="d.updatedAt">更新于 {{ formatTime(d.updatedAt) }}</span>
        </div>
        <div class="dl__cardFoot">
          <el-button
            v-if="d.status === 'pending'"
            size="small"
            type="primary"
            :icon="MagicStick"
            @click="onGenerateClick(d, $event)"
          >
            AI 生成
          </el-button>
          <el-button
            v-else-if="d.status === 'draft'"
            size="small"
            :icon="MagicStick"
            plain
            @click="onGenerateClick(d, $event)"
          >
            重新生成
          </el-button>
          <el-button
            size="small"
            :icon="d.status === 'published' ? View : EditPen"
            @click.stop="onOpen(d)"
          >
            {{ d.status === 'published' ? '阅读' : '编辑' }}
          </el-button>
        </div>
      </article>
    </div>

    <TemplatePicker
      v-model="showPicker"
      :project-id="projectId"
      :subjects="subjects"
      @created="onCreated"
    />
  </div>
</template>

<style scoped>
.dl { max-width: 1100px; margin: 0 auto; }
.dl__head {
  display: flex; align-items: center; gap: 16px;
  padding-bottom: 12px; border-bottom: 1px solid #e5e7eb; margin-bottom: 16px;
}
.dl__title { flex: 1; }
.dl__title h2 { margin: 0; }
.muted { color: #6b7280; font-size: 13px; margin: 4px 0 0; }
.dl__bar {
  display: flex; gap: 12px; align-items: center; flex-wrap: wrap;
  padding: 12px; background: #fff; border-radius: 8px;
  border: 1px solid #e5e7eb; margin-bottom: 16px;
}
.dl__count { color: #9ca3af; font-size: 12px; margin-left: auto; }
.dl__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.dl__card {
  background: #fff; border: 1px solid #e5e7eb; border-radius: 8px;
  padding: 16px; cursor: pointer; transition: all 0.15s;
  display: flex; flex-direction: column; gap: 8px;
}
.dl__card:hover {
  border-color: #2563eb;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.08);
  transform: translateY(-1px);
}
.dl__cardHead { display: flex; align-items: center; gap: 8px; }
.dl__emoji { font-size: 22px; }
.dl__cardTitle {
  margin: 0; font-size: 16px; color: #1f2937; line-height: 1.4;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.dl__cardMeta { color: #4b5563; font-size: 13px; }
.dl__cardMeta--sub { color: #9ca3af; font-size: 12px; }
.dl__tplName { font-weight: 500; }
.dl__sep { margin: 0 4px; color: #d1d5db; }
.dl__cardFoot {
  display: flex; gap: 8px; margin-top: 4px;
  padding-top: 8px; border-top: 1px dashed #e5e7eb;
}
</style>
