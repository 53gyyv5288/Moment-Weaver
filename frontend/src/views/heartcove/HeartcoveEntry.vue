<script setup lang="ts">
/**
 * 心声信箱 · 入口页
 *
 * 设计原则：
 *   - 不在主导航显眼位置；本入口挂在 Layout 底部低调入口
 *   - 后端单次聚合接口 /heartcove/my-enabled-subjects，前端只调一次
 *   - 顶部固定 AI 标识 banner（合规底线）
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { disableHeartcove, listMyEnabledHeartcoveSubjects, type EnabledHeartcoveSubjectVO } from '@/api/heartcove'

const router = useRouter()
const items = ref<EnabledHeartcoveSubjectVO[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    items.value = await listMyEnabledHeartcoveSubjects()
  } catch (e: any) {
    console.error(e)
    ElMessage.error(e?.message || '加载心声信箱列表失败')
  } finally {
    loading.value = false
  }
}

function enter(subjectId: number) {
  router.push({ name: 'heartcove-chat', params: { subjectId } })
}

async function onDisable(subjectId: number) {
  try {
    await disableHeartcove(subjectId)
    ElMessage.success('已关闭心声信箱')
    load()
  } catch (e: any) {
    ElMessage.error(e?.message || '关闭失败')
  }
}

// 按 projectId 分组渲染（同一个项目的多个人物放一起）
const grouped = ref<Array<{ projectId: number; projectName: string; items: EnabledHeartcoveSubjectVO[] }>>([])

function regroup() {
  const map = new Map<number, { projectId: number; projectName: string; items: EnabledHeartcoveSubjectVO[] }>()
  for (const it of items.value) {
    if (!map.has(it.projectId)) {
      map.set(it.projectId, { projectId: it.projectId, projectName: it.projectName, items: [] })
    }
    map.get(it.projectId)!.items.push(it)
  }
  grouped.value = Array.from(map.values())
}

onMounted(async () => {
  await load()
  regroup()
})
</script>

<template>
  <div class="hc">
    <!-- 合规 banner：AI 身份明示 -->
    <div class="hc__banner">
      <span class="hc__banner-icon">⚠️</span>
      <span>心声信箱的回应均由 AI 基于既往采访素材生成，不代表逝者本人当下立场。</span>
    </div>

    <header class="hc__header">
      <h1 class="hc__title">心声信箱</h1>
      <p class="hc__sub">一个安静的地方，和先辈的人聊聊。</p>
    </header>

    <el-empty v-if="!loading && items.length === 0" description="暂未开启任何心声邮箱" />

    <el-skeleton v-else-if="loading" :rows="4" animated />

    <section v-for="card in grouped" :key="card.projectId" class="hc__project">
      <h2 class="hc__project-title">{{ card.projectName }}</h2>
      <div class="hc__grid">
        <article
          v-for="s in card.items"
          :key="s.subjectId"
          class="hc__card"
          @click="enter(s.subjectId)"
        >
          <div class="hc__avatar">📜</div>
          <div class="hc__body">
            <div class="hc__name">{{ s.subjectDisplayName }}</div>
            <div class="hc__relation" v-if="s.subjectRelation">· {{ s.subjectRelation }} ·</div>
            <div class="hc__meta">
              <el-tag size="small" effect="plain" type="success">已开启</el-tag>
              <span class="hc__time">
                {{ s.heartcoveEnabledAt?.slice(0, 10) }} 开启
              </span>
            </div>
          </div>
          <el-button
            text
            size="small"
            class="hc__close"
            @click.stop="onDisable(s.subjectId)"
          >关闭</el-button>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.hc {
  max-width: 920px;
  margin: 0 auto;
  padding: 8px 0 48px;
}
.hc__banner {
  background: rgba(217, 119, 6, 0.08);
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  padding: 12px 16px;
  font-size: 13px;
  color: var(--mw-text-secondary);
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 24px;
}
.hc__banner-icon {
  font-size: 16px;
  flex-shrink: 0;
}
.hc__header {
  margin-bottom: 32px;
}
.hc__title {
  font-size: 24px;
  font-weight: 600;
  color: var(--mw-text);
  margin: 0 0 6px;
}
.hc__sub {
  font-size: 14px;
  color: var(--mw-text-muted);
  margin: 0;
}
.hc__project {
  margin-bottom: 32px;
}
.hc__project-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--mw-text-secondary);
  margin: 0 0 12px;
}
.hc__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.hc__card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  background: var(--mw-surface);
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  cursor: pointer;
  transition: box-shadow 0.2s, border-color 0.2s;
  position: relative;
}
.hc__card:hover {
  box-shadow: var(--mw-shadow);
  border-color: var(--mw-primary);
}
.hc__avatar {
  width: 48px;
  height: 48px;
  background: var(--mw-bg);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
}
.hc__body {
  flex: 1;
  min-width: 0;
}
.hc__name {
  font-size: 16px;
  font-weight: 600;
  color: var(--mw-text);
}
.hc__relation {
  font-size: 12px;
  color: var(--mw-text-muted);
  margin-top: 2px;
}
.hc__meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.hc__time {
  font-size: 11px;
  color: var(--mw-text-muted);
}
.hc__close {
  position: absolute;
  top: 8px;
  right: 8px;
  font-size: 12px;
  color: var(--mw-text-muted);
  opacity: 0;
  transition: opacity 0.2s;
}
.hc__card:hover .hc__close {
  opacity: 1;
}
</style>