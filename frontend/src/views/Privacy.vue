<script setup lang="ts">
/**
 * 隐私政策 / 用户协议（M5-B.3 隐私政策）。
 * 公开页：注册时勾选框跳转的链接。
 * 路由：/privacy
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import consentMd from '@/assets/consent.md?raw'

const router = useRouter()
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const consentHtml = computed(() => md.render(consentMd))
</script>

<template>
  <main class="privacy">
    <header class="privacy__head">
      <el-button text @click="router.back()">← 返回</el-button>
      <h1>隐私政策 / 用户协议</h1>
      <p class="muted">版本：v1.0-20250612 · 适用于 Moment Weaver 全平台</p>
    </header>

    <article class="privacy__body" v-html="consentHtml" />
  </main>
</template>

<style scoped>
.privacy {
  max-width: 820px;
  margin: 0 auto;
  padding: 32px 16px 80px;
}
.privacy__head h1 {
  margin: 8px 0 4px;
  font-size: 24px;
  color: #1f2937;
}
.muted { color: #6b7280; font-size: 13px; margin: 0 0 16px; }
.privacy__body {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 24px 32px;
  line-height: 1.7;
  color: #1f2937;
}
.privacy__body :deep(h1) { font-size: 22px; margin-top: 0; }
.privacy__body :deep(h2) { font-size: 18px; margin-top: 28px; }
.privacy__body :deep(ul) { padding-left: 24px; }
.privacy__body :deep(blockquote) {
  border-left: 3px solid #d1d5db;
  margin: 0;
  padding: 4px 12px;
  color: #6b7280;
  background: #f9fafb;
}
</style>
