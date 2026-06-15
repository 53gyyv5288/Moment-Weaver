<script setup lang="ts">
/**
 * 成稿编辑器 (M4)。
 * 每章节：标题 + ProvenanceBadge + 内容（contenteditable）/ 重写 / 状态栏
 * 顶部：返回 / 标题 / 状态 / AI 整篇生成 / 发布
 */
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, View, Check, Refresh, ChatLineRound } from '@element-plus/icons-vue'
import {
  getDraft,
  updateSection,
  generateDraft,
  publishDraft,
  REWRITE_STYLE_LABELS,
  type NarrativeDraftVO,
  type SectionVO,
  type RewriteStyle,
} from '@/api/draft'
import ProvenanceBadge from '@/components/ProvenanceBadge.vue'

const route = useRoute()
const router = useRouter()

const draftId = computed(() => route.params.did as string)
const draft = ref<NarrativeDraftVO | null>(null)
const loading = ref(false)
const generating = ref(false)
const publishing = ref(false)

/** 编辑态管理：sectionId -> 是否在编辑中 */
const editingSectionId = ref<string | null>(null)
const editingContent = ref('')
const savingSection = ref<string | null>(null)

/** 重写弹窗 */
const rewriteSectionId = ref<string | null>(null)
const rewriteStyle = ref<RewriteStyle>('warmer')
const rewriting = ref(false)

/**
 * AI 整篇生成进度（M4 UX）。
 *
 * 整篇 6 章节在 MiniMax-M3 上常见 120~180s（person）/ 240~360s（family）。
 * 用「时间驱动的假进度」给用户视觉反馈，避免 2~6 分钟黑屏让人以为卡死。
 * bar 永远停在 95%，等真实响应回来再跳 100%。
 */
const generatingPercent = ref(0)
const generatingElapsed = ref(0)
const generatingStage = ref('')
let generatingTimer: number | null = null

function startGeneratingProgress() {
  generatingElapsed.value = 0
  generatingPercent.value = 0
  generatingStage.value = '正在整理事实...'
  // 家族成稿 3 subjects 更慢，给到 5 分钟；人物成稿 2~3 分钟
  const isFamily = draft.value?.templateId === 'family-template-v1'
  const fullSeconds = isFamily ? 300 : 150
  // 阶段秒数：5 个分界点，等比随 fullSeconds 放缩
  // 家族用 5 分钟（300s），各阶段按 1/30, 1/6, 2/5, 7/10, 14/15 分布
  const s = isFamily
    ? [10, 50, 120, 210, 280]   // 家族
    : [5, 25, 60, 105, 140]     // 人物
  generatingTimer = window.setInterval(() => {
    generatingElapsed.value++
    const p = Math.min(generatingElapsed.value / fullSeconds, 0.95)
    generatingPercent.value = Math.round(p * 100)
    if (generatingElapsed.value < s[0]) {
      generatingStage.value = '正在整理事实...'
    } else if (generatingElapsed.value < s[1]) {
      generatingStage.value = 'AI 正在分析人物...'
    } else if (generatingElapsed.value < s[2]) {
      generatingStage.value = 'AI 正在撰写开篇与童年...'
    } else if (generatingElapsed.value < s[3]) {
      generatingStage.value = 'AI 正在撰写中段章节...'
    } else if (generatingElapsed.value < s[4]) {
      generatingStage.value = 'AI 正在撰写结语...'
    } else {
      generatingStage.value = '即将完成，请稍候...'
    }
  }, 1000)
}

function stopGeneratingProgress(success: boolean) {
  if (generatingTimer !== null) {
    clearInterval(generatingTimer)
    generatingTimer = null
  }
  if (success) {
    generatingPercent.value = 100
    generatingStage.value = '✓ 生成完成'
    // 给用户看一眼 100%，再清掉
    window.setTimeout(() => {
      generatingPercent.value = 0
      generatingElapsed.value = 0
      generatingStage.value = ''
    }, 1500)
  } else {
    // 失败：直接清掉，不显示 100%
    generatingPercent.value = 0
    generatingElapsed.value = 0
    generatingStage.value = ''
  }
}

function formatElapsed(s: number) {
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const r = s % 60
  return r === 0 ? `${m} 分` : `${m} 分 ${r} 秒`
}

onUnmounted(() => {
  // 离开页面时清掉定时器，避免内存泄漏
  if (generatingTimer !== null) clearInterval(generatingTimer)
})

const sortedSections = computed<SectionVO[]>(() => {
  if (!draft.value) return []
  return [...draft.value.sections].sort((a, b) => (a.order || 0) - (b.order || 0))
})

const totalChars = computed(() => {
  if (!draft.value) return 0
  return draft.value.sections.reduce((sum, s) => sum + (s.content?.length || 0), 0)
})

const canPublish = computed(() => {
  if (!draft.value) return false
  if (draft.value.status === 'published') return false
  if (draft.value.status === 'pending') return false
  // 至少有一节有内容
  return draft.value.sections.some(s => (s.content || '').trim().length > 0)
})

async function load() {
  loading.value = true
  try {
    const { data } = await getDraft(draftId.value)
    if (data?.code === 0) {
      draft.value = data.data
    } else {
      ElMessage.error(data?.message || '成稿不存在')
      router.replace('/projects')
    }
  } finally {
    loading.value = false
  }
}

function startEdit(s: SectionVO) {
  editingSectionId.value = s.sectionId
  editingContent.value = s.content || ''
}

function cancelEdit() {
  editingSectionId.value = null
  editingContent.value = ''
}

async function saveEdit(s: SectionVO) {
  if (savingSection.value) return
  if (editingContent.value === s.content) {
    cancelEdit()
    return
  }
  savingSection.value = s.sectionId
  try {
    const { data } = await updateSection(
      draftId.value,
      s.sectionId,
      { content: editingContent.value },
      draft.value?.version,
    )
    if (data?.code === 0) {
      draft.value = data.data
      ElMessage.success('已保存')
      cancelEdit()
    } else {
      ElMessage.error(data?.message || '保存失败')
    }
  } finally {
    savingSection.value = null
  }
}

function openRewrite(s: SectionVO) {
  rewriteSectionId.value = s.sectionId
  rewriteStyle.value = 'warmer'
}

async function onRewriteConfirm() {
  if (!rewriteSectionId.value || !draft.value) return
  const sid = rewriteSectionId.value
  const s = draft.value.sections.find(x => x.sectionId === sid)
  if (!s) return
  rewriting.value = true
  try {
    const { data } = await updateSection(
      draftId.value,
      sid,
      { rewriteStyle: rewriteStyle.value },
      draft.value.version,
    )
    if (data?.code === 0) {
      draft.value = data.data
      ElMessage.success('已重写')
      rewriteSectionId.value = null
    } else {
      ElMessage.error(data?.message || '重写失败')
    }
  } finally {
    rewriting.value = false
  }
}

async function onGenerate() {
  if (!draft.value) return
  await ElMessageBox.confirm(
    '将用 AI 整篇生成所有章节，通常需要 1-3 分钟。已有内容会被覆盖。',
    'AI 整篇生成',
    { type: 'info' },
  ).catch(() => null)
  generating.value = true
  startGeneratingProgress()
  let success = false
  try {
    const { data } = await generateDraft(draftId.value)
    if (data?.code === 0) {
      draft.value = data.data
      ElMessage.success('生成完成')
      success = true
    } else {
      ElMessage.error(data?.message || '生成失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '生成失败，请重试')
  } finally {
    stopGeneratingProgress(success)
    generating.value = false
  }
}

async function onPublish() {
  if (!draft.value) return
  await ElMessageBox.confirm(
    '发布后其他人将可阅读。是否继续？',
    '发布成稿',
    { type: 'info' },
  ).catch(() => null)
  publishing.value = true
  try {
    const { data } = await publishDraft(draftId.value, {})
    if (data?.code === 0) {
      draft.value = data.data
      ElMessage.success('已发布')
    } else {
      ElMessage.error(data?.message || '发布失败')
    }
  } finally {
    publishing.value = false
  }
}

function onView() {
  router.push(`/drafts/${draftId.value}/read`)
}

function onBack() {
  router.push(`/projects/${draft.value?.projectId}/drafts`)
}

function statusLabel(s?: string) {
  return ({ pending: '待生成', draft: '草稿', published: '已发布', archived: '已归档' } as any)[s || ''] || s || ''
}
function statusType(s?: string) {
  if (s === 'published') return 'success'
  if (s === 'pending') return 'warning'
  if (s === 'draft') return 'info'
  return ''
}

function formatTime(s?: string | null) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)

// 切换编辑态时滚动到对应区域
function scrollToSection(sid: string) {
  nextTick(() => {
    const el = document.getElementById(`sec-${sid}`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}
</script>

<template>
  <div class="de" v-loading="loading">
    <header class="de__head">
      <el-button text @click="onBack">← 返回成稿列表</el-button>
      <div class="de__title">
        <h2>{{ draft?.title || '成稿编辑器' }}</h2>
        <div class="de__meta">
          <el-tag size="small" :type="statusType(draft?.status)" effect="plain">
            {{ statusLabel(draft?.status) }}
          </el-tag>
          <span class="de__chars">共 {{ totalChars }} 字 · {{ sortedSections.length }} 章</span>
          <span v-if="draft?.version" class="de__ver">v{{ draft.version }}</span>
        </div>
      </div>
      <div class="de__actions">
        <el-button
          :loading="generating"
          :icon="MagicStick"
          @click="onGenerate"
        >
          {{ draft?.status === 'pending' ? 'AI 整篇生成' : 'AI 重新生成' }}
        </el-button>
        <el-button v-if="draft?.status === 'published'" :icon="View" @click="onView">阅读</el-button>
        <el-button
          v-else
          type="primary"
          :loading="publishing"
          :disabled="!canPublish"
          @click="onPublish"
        >
          发布
        </el-button>
      </div>
    </header>

    <!-- AI 整篇生成进度条（generating=true 时显示） -->
    <div v-if="generating || generatingStage" class="de__progress">
      <el-progress
        :percentage="generatingPercent"
        :stroke-width="10"
        :duration="1"
        :show-text="false"
        :status="generatingPercent === 100 ? 'success' : 'primary'"
      />
      <div class="de__progressRow">
        <span class="de__progressStage">{{ generatingStage }}</span>
        <span class="de__progressElapsed">{{ formatElapsed(generatingElapsed) }}</span>
      </div>
      <div class="de__progressHint">
        6 章节中文长文通常需要 1-3 分钟，请勿关闭页面
      </div>
    </div>

    <el-alert
      v-if="draft?.status === 'pending'"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #title>这是空成稿</template>
      点击右上角「AI 整篇生成」让 AI 写入内容；或下面对单个章节点「重写」。
    </el-alert>

    <el-alert
      v-else-if="draft?.status === 'published'"
      type="success"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #title>已发布</template>
      发布于 {{ formatTime(draft.publishedAt) }}。读者可通过「阅读」按钮查看成稿。
    </el-alert>

    <section
      v-for="s in sortedSections"
      :key="s.sectionId"
      :id="`sec-${s.sectionId}`"
      class="de__section"
    >
      <header class="de__secHead">
        <div class="de__secTitle">
          <span class="de__secIdx">第 {{ (s.order || 0) + 1 }} 章</span>
          <h3>{{ s.sectionTitle }}</h3>
        </div>
        <div class="de__secTags">
          <ProvenanceBadge
            :provenance="s.provenance"
            :ai-generated="s.aiGenerated"
            :rewrite-count="s.rewriteCount"
            :manually-edited-at="s.manuallyEditedAt"
          />
          <span v-if="s.targetCharsMin" class="de__hint">建议 {{ s.targetCharsMin }} ~ {{ s.targetCharsMax }} 字</span>
        </div>
      </header>

      <div v-if="editingSectionId !== s.sectionId" class="de__content" @click="startEdit(s)">
        <div v-if="s.content" class="de__text">{{ s.content }}</div>
        <div v-else class="de__empty">（空白章节）点击此处开始撰写</div>
      </div>
      <div v-else class="de__editing">
        <el-input
          v-model="editingContent"
          type="textarea"
          :rows="10"
          maxlength="8000"
          show-word-limit
          placeholder="开始撰写..."
        />
        <div class="de__editingFoot">
          <el-button @click="cancelEdit">取消</el-button>
          <el-button
            type="primary"
            :loading="savingSection === s.sectionId"
            :icon="Check"
            @click="saveEdit(s)"
          >
            保存
          </el-button>
        </div>
      </div>

      <footer class="de__secFoot">
        <div class="de__secInfo">
          <span v-if="s.factsUsed && s.factsUsed.length" class="de__facts">
            <el-icon><ChatLineRound /></el-icon>
            引用 {{ s.factsUsed.length }} 条事实
          </span>
          <span v-if="s.lastRewriteStyle" class="de__meta">
            上次重写风格：{{ REWRITE_STYLE_LABELS[s.lastRewriteStyle] || s.lastRewriteStyle }}
          </span>
        </div>
        <div class="de__secActions">
          <el-button
            size="small"
            :icon="Refresh"
            :disabled="draft?.status === 'published' || generating || rewriting"
            @click="openRewrite(s)"
          >
            AI 重写
          </el-button>
          <el-button
            v-if="editingSectionId !== s.sectionId"
            size="small"
            @click="startEdit(s)"
          >
            编辑
          </el-button>
        </div>
      </footer>
    </section>

    <!-- 重写风格选择弹窗 -->
    <el-dialog
      v-model="rewriteSectionId"
      title="选择重写风格"
      width="420px"
      :close-on-click-modal="false"
    >
      <el-radio-group v-model="rewriteStyle" style="display: flex; flex-direction: column; gap: 8px">
        <el-radio value="warmer" size="large">更温暖 · 情感更细腻</el-radio>
        <el-radio value="concise" size="large">更简洁 · 删减冗余</el-radio>
        <el-radio value="vivid" size="large">更生动 · 加入细节描写</el-radio>
        <el-radio value="formal" size="large">更正式 · 适合归档</el-radio>
      </el-radio-group>
      <p class="muted">重写会基于当前 factsSnapshot 由 AI 重新生成该章节，provenance 会重置为「AI 生成」。</p>
      <template #footer>
        <el-button @click="rewriteSectionId = null">取消</el-button>
        <el-button type="primary" :loading="rewriting" @click="onRewriteConfirm">重写</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.de { max-width: 880px; margin: 0 auto; }
.de__head {
  display: flex; align-items: flex-start; gap: 16px;
  padding-bottom: 12px; border-bottom: 1px solid #e5e7eb; margin-bottom: 16px;
}
.de__title { flex: 1; min-width: 0; }
.de__title h2 { margin: 0; }
.de__meta { display: flex; align-items: center; gap: 12px; margin-top: 4px; }
.de__chars { color: #6b7280; font-size: 13px; }
.de__ver { color: #9ca3af; font-size: 12px; }
.de__actions { display: flex; gap: 8px; }
.muted { color: #6b7280; font-size: 12px; }

.de__section {
  background: #fff; border: 1px solid #e5e7eb; border-radius: 8px;
  padding: 20px 24px; margin-bottom: 16px;
}
.de__secHead {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-bottom: 12px;
}
.de__secTitle { display: flex; align-items: center; gap: 8px; flex: 1; }
.de__secIdx { color: #9ca3af; font-size: 12px; font-weight: 500; }
.de__secTitle h3 { margin: 0; font-size: 17px; color: #1f2937; }
.de__secTags { display: flex; align-items: center; gap: 8px; }
.de__hint { color: #9ca3af; font-size: 12px; }

.de__content {
  padding: 12px 14px; min-height: 60px; border-radius: 6px;
  background: #f9fafb; border: 1px dashed #e5e7eb;
  cursor: text; line-height: 1.8;
  white-space: pre-wrap; word-break: break-word;
  color: #1f2937;
}
.de__content:hover { border-color: #93c5fd; background: #f3f4f6; }
.de__text { white-space: pre-wrap; }
.de__empty { color: #9ca3af; font-style: italic; }

.de__editing { display: flex; flex-direction: column; gap: 8px; }
.de__editingFoot { display: flex; gap: 8px; justify-content: flex-end; }

.de__secFoot {
  display: flex; align-items: center; gap: 12px;
  margin-top: 12px; padding-top: 10px;
  border-top: 1px dashed #e5e7eb;
  flex-wrap: wrap;
}
.de__secInfo { flex: 1; display: flex; gap: 12px; flex-wrap: wrap; color: #6b7280; font-size: 12px; }
.de__facts { display: inline-flex; align-items: center; gap: 4px; }
.de__secActions { display: flex; gap: 8px; }

/* AI 整篇生成进度面板 */
.de__progress {
  background: linear-gradient(135deg, #eff6ff 0%, #f0f9ff 100%);
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  padding: 16px 20px;
  margin-bottom: 16px;
  box-shadow: 0 1px 3px rgba(59, 130, 246, 0.06);
}
.de__progressRow {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 14px; color: #1e40af; margin-top: 10px; margin-bottom: 4px;
}
.de__progressStage { font-weight: 500; }
.de__progressElapsed {
  color: #6b7280; font-size: 13px;
  font-variant-numeric: tabular-nums;  /* 数字等宽，避免跳 */
  font-feature-settings: "tnum";
}
.de__progressHint { color: #6b7280; font-size: 12px; }
</style>
