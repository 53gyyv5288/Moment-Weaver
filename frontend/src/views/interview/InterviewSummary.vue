<script setup lang="ts">
/**
 * 采访摘要页（M3）。展示 AI 生成的标题 / 金句 / 关键时间点。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getInterviewSession } from '@/api/interview'
import { summarizeSession } from '@/api/summary'
import type { InterviewSessionVO, InterviewSummaryVO } from '@/types/api'

const route = useRoute()
const router = useRouter()
const sessionId = computed(() => route.params.id as string)

const session = ref<InterviewSessionVO | null>(null)
const loading = ref(false)
const regenerating = ref(false)

const summary = computed<InterviewSummaryVO | null>(() => {
  const s = session.value?.summary
  if (!s) return null
  return {
    title: s.title,
    goldenQuotes: s.goldenQuotes || [],
    keyMoments: s.keyMoments || [],
    generatedAt: s.generatedAt,
  }
})

async function load() {
  loading.value = true
  try {
    const { data } = await getInterviewSession(sessionId.value)
    if (data && data.code === 0) {
      session.value = data.data
    } else {
      ElMessage.error(data?.message || '会话不存在')
      router.replace('/projects')
    }
  } finally {
    loading.value = false
  }
}

async function onRegenerate() {
  regenerating.value = true
  try {
    const { data } = await summarizeSession(sessionId.value)
    if (data?.code === 0) {
      session.value = data.data
      ElMessage.success('已重新生成')
    } else {
      ElMessage.error(data?.message || '生成失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '生成失败')
  } finally {
    regenerating.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="sm" v-loading="loading">
    <header class="sm__head">
      <el-button text @click="router.back()">← 返回</el-button>
      <div class="sm__title">
        <h2>{{ session?.subjectDisplayName }} · 采访摘要</h2>
        <p class="muted">项目：{{ session?.projectName }}</p>
      </div>
      <el-button
        :loading="regenerating"
        type="primary"
        plain
        @click="onRegenerate"
      >
        {{ summary ? '重新生成' : '生成摘要' }}
      </el-button>
    </header>

    <div v-if="!summary" class="sm__empty">
      <p>暂无摘要。点击右上角「生成摘要」可立即让 AI 总结本次对话。</p>
      <p class="muted">（结束采访时也会自动生成一次。）</p>
    </div>

    <section v-else class="sm__body">
      <article class="sm__titleCard">
        <div class="sm__tag">标题</div>
        <h1>{{ summary.title }}</h1>
        <p v-if="summary.generatedAt" class="muted">
          生成于 {{ summary.generatedAt }}
        </p>
      </article>

      <article v-if="summary.goldenQuotes.length" class="sm__card">
        <div class="sm__tag sm__tag--gold">金句</div>
        <ul class="sm__quotes">
          <li v-for="(q, i) in summary.goldenQuotes" :key="i" class="sm__quote">
            <span class="sm__quoteMark">「</span>{{ q }}<span class="sm__quoteMark">」</span>
          </li>
        </ul>
      </article>

      <article v-if="summary.keyMoments.length" class="sm__card">
        <div class="sm__tag sm__tag--time">关键时间点</div>
        <ol class="sm__moments">
          <li v-for="(m, i) in summary.keyMoments" :key="i" class="sm__moment">
            <span class="sm__ts">{{ m.timestamp || `第 ${i + 1} 节点` }}</span>
            <span class="sm__text">{{ m.text }}</span>
          </li>
        </ol>
      </article>
    </section>
  </div>
</template>

<style scoped>
.sm { max-width: 760px; margin: 0 auto; }
.sm__head {
  display: flex; align-items: center; gap: 16px;
  padding-bottom: 12px; border-bottom: 1px solid #e5e7eb;
}
.sm__title { flex: 1; }
.sm__title h2 { margin: 0; }
.muted { color: #9ca3af; font-size: 12px; margin: 4px 0 0; }
.sm__empty {
  margin-top: 60px; text-align: center; color: #6b7280;
}
.sm__body {
  margin-top: 24px;
  display: flex; flex-direction: column; gap: 16px;
}
.sm__titleCard, .sm__card {
  background: #fff; border: 1px solid #e5e7eb; border-radius: 8px;
  padding: 20px 24px;
}
.sm__titleCard h1 { margin: 4px 0 0; font-size: 22px; color: #1f2937; }
.sm__tag {
  display: inline-block; font-size: 11px; font-weight: 600;
  color: #2563eb; background: #eff6ff;
  padding: 2px 8px; border-radius: 4px; letter-spacing: 0.5px;
}
.sm__tag--gold { color: #b45309; background: #fef3c7; }
.sm__tag--time { color: #047857; background: #d1fae5; }
.sm__quotes { list-style: none; padding: 0; margin: 12px 0 0; display: flex; flex-direction: column; gap: 10px; }
.sm__quote { color: #1f2937; line-height: 1.7; }
.sm__quoteMark { color: #9ca3af; }
.sm__moments { list-style: none; padding: 0; margin: 12px 0 0; display: flex; flex-direction: column; gap: 8px; }
.sm__moment {
  display: flex; gap: 12px; padding: 10px 12px;
  background: #f9fafb; border-radius: 6px;
}
.sm__ts {
  flex-shrink: 0; min-width: 100px;
  color: #047857; font-weight: 500;
}
.sm__text { color: #1f2937; line-height: 1.6; }
</style>