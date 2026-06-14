<script setup lang="ts">
/**
 * 时间线单条详情（M3）。绝大多数时候点条目已直接跳到目标页（采访 / 摘要 / 大图）；
 * 此页面保留作为「未知类型」事件的兜底展示。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const eventId = computed(() => route.params.eventId as string)

// 暂存：后续可补充「GET /api/v1/timeline/{id}」单条详情端点（M3 不必须，先用列表）。
</script>

<template>
  <div class="md">
    <el-button text @click="router.back()">← 返回</el-button>
    <h2>事件详情</h2>
    <p class="muted">事件 ID：{{ eventId }}</p>
    <p class="muted">提示：点击时间线上的事件会自动跳到对应的采访消息 / 素材大图 / 摘要页。</p>
  </div>
</template>

<style scoped>
.md { max-width: 720px; margin: 0 auto; }
.muted { color: #9ca3af; font-size: 13px; }
</style>