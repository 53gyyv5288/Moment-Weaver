<script setup lang="ts">
/**
 * 章节来源标识 Badge (M4)。
 * 4 种 provenance：ai / human / mixed / system
 * 额外：rewriteCount 提示、manuallyEditedAt 提示
 */
import { computed } from 'vue'
import type { SectionProvenance } from '@/types/api'

const props = defineProps<{
  provenance?: SectionProvenance | null
  aiGenerated?: boolean
  rewriteCount?: number
  manuallyEditedAt?: string | null
}>()

const p = computed<SectionProvenance>(() => (props.provenance || 'system') as SectionProvenance)

const badgeType = computed(() => {
  switch (p.value) {
    case 'ai': return 'info'
    case 'mixed': return 'warning'
    case 'human': return 'success'
    case 'system': return ''
    default: return ''
  }
})

const badgeText = computed(() => {
  switch (p.value) {
    case 'ai': {
      const n = props.rewriteCount || 0
      return n > 0 ? `AI 生成 · 已重写 ${n} 次` : 'AI 生成'
    }
    case 'mixed': return 'AI 起草 · 已编辑'
    case 'human': return '人工撰写'
    case 'system': return '系统生成'
    default: return '未知'
  }
})

const badgeTitle = computed(() => {
  if (props.manuallyEditedAt) {
    const d = new Date(props.manuallyEditedAt)
    if (!isNaN(d.getTime())) {
      return `最后编辑：${d.toLocaleString('zh-CN')}`
    }
  }
  if (p.value === 'ai' && (props.rewriteCount || 0) > 0) {
    return '该章节已由 AI 重写过'
  }
  if (p.value === 'system') {
    return '由系统自动填充（如标题、摘要）'
  }
  return ''
})
</script>

<template>
  <el-tag
    :type="badgeType"
    size="small"
    effect="plain"
    :title="badgeTitle"
  >
    {{ badgeText }}
  </el-tag>
</template>
