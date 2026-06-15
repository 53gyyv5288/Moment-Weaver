<script setup lang="ts">
/**
 * 成稿阅读器 (M4)。
 * 适合发布的优雅阅读视图；章节按 order 排序，每章顶部带 ProvenanceBadge。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { EditPen } from '@element-plus/icons-vue'
import { getDraft } from '@/api/draft'
import type { NarrativeDraftVO, SectionVO } from '@/types/api'
import ProvenanceBadge from '@/components/ProvenanceBadge.vue'

const route = useRoute()
const router = useRouter()

const draftId = computed(() => route.params.did as string)
const draft = ref<NarrativeDraftVO | null>(null)
const loading = ref(false)

const sortedSections = computed<SectionVO[]>(() => {
  if (!draft.value) return []
  return [...draft.value.sections].sort((a, b) => (a.order || 0) - (b.order || 0))
})

const TEMPLATE_LABELS: Record<string, string> = {
  'person-template-v1': '人物小传',
  'family-template-v1': '家族小传',
}

async function load() {
  loading.value = true
  try {
    const { data } = await getDraft(draftId.value)
    if (data?.code === 0) draft.value = data.data
    else ElMessage.error(data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function onEdit() {
  router.push(`/drafts/${draftId.value}/edit`)
}

function onBack() {
  if (draft.value?.projectId) {
    router.push(`/projects/${draft.value.projectId}/drafts`)
  } else {
    router.back()
  }
}

function formatTime(s?: string | null) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div class="dr" v-loading="loading">
    <header class="dr__head">
      <el-button text @click="onBack">← 返回</el-button>
      <el-button
        v-if="draft && draft.status !== 'published'"
        :icon="EditPen"
        plain
        @click="onEdit"
      >
        去编辑
      </el-button>
    </header>

    <article v-if="draft" class="dr__body">
      <div class="dr__cover">
        <el-tag size="small" effect="plain">{{ TEMPLATE_LABELS[draft.templateId] || draft.templateId }}</el-tag>
        <h1 class="dr__title">{{ draft.title || '（未命名）' }}</h1>
        <div v-if="draft.subjectDisplayNames && draft.subjectDisplayNames.length" class="dr__subjects">
          献给：{{ draft.subjectDisplayNames.join('、') }}
        </div>
        <p v-if="draft.publishedAt" class="dr__published">发布于 {{ formatTime(draft.publishedAt) }}</p>
        <p v-else-if="draft.updatedAt" class="dr__published">最后更新于 {{ formatTime(draft.updatedAt) }}</p>
      </div>

      <section
        v-for="s in sortedSections"
        :key="s.sectionId"
        class="dr__sec"
      >
        <div class="dr__secHead">
          <h2>{{ s.sectionTitle }}</h2>
          <ProvenanceBadge
            :provenance="s.provenance"
            :ai-generated="s.aiGenerated"
            :rewrite-count="s.rewriteCount"
            :manually-edited-at="s.manuallyEditedAt"
          />
        </div>
        <div v-if="s.content" class="dr__text">{{ s.content }}</div>
        <div v-else class="dr__empty">（该章节暂无内容）</div>
      </section>

      <footer class="dr__foot">
        <p class="dr__sign">— 完 —</p>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.dr { max-width: 720px; margin: 0 auto; padding: 0 8px; }
.dr__head {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 24px;
}

.dr__body {
  background: #fff; border-radius: 12px; padding: 56px 64px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.dr__cover {
  text-align: center; padding-bottom: 32px;
  border-bottom: 1px dashed #e5e7eb; margin-bottom: 40px;
}
.dr__title {
  margin: 12px 0 8px; font-size: 32px; color: #1f2937; font-weight: 700;
  letter-spacing: 1px;
}
.dr__subjects { color: #6b7280; font-size: 14px; margin: 0 0 8px; }
.dr__published { color: #9ca3af; font-size: 12px; margin: 0; }

.dr__sec { margin-bottom: 40px; }
.dr__secHead {
  display: flex; align-items: center; gap: 12px;
  margin-bottom: 16px;
}
.dr__secHead h2 {
  margin: 0; font-size: 20px; color: #1f2937;
  border-left: 4px solid #2563eb; padding-left: 10px;
}
.dr__text {
  color: #374151; font-size: 16px; line-height: 1.9;
  white-space: pre-wrap; word-break: break-word;
  text-align: justify;
}
.dr__empty { color: #9ca3af; font-style: italic; }

.dr__foot { text-align: center; margin-top: 48px; padding-top: 24px;
  border-top: 1px dashed #e5e7eb; }
.dr__sign { color: #9ca3af; font-size: 14px; letter-spacing: 4px; }

@media (max-width: 768px) {
  .dr__body { padding: 32px 20px; }
  .dr__title { font-size: 24px; }
}
</style>
