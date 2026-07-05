<script setup lang="ts">
/**
 * 公开分享阅读页（M5-A）。
 * 路由：/share/:token
 * 无 Layout、无 JWT。三种状态：
 *   1) loading → preview
 *   2) scope=password 且未 verify → 密码门
 *   3) 通过验证/无需密码 → 完整内容 + 「含 AI 内容」横幅
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  previewPublicShare,
  verifyPublicShare,
  accessPublicShare,
  exportPublicPdf,
  type PublicShareVO,
} from '@/api/share'
import ProvenanceBadge from '@/components/ProvenanceBadge.vue'

const route = useRoute()
const token = computed(() => route.params.token as string)

type Phase = 'loading' | 'preview' | 'password' | 'content' | 'error'
const phase = ref<Phase>('loading')
const share = ref<PublicShareVO | null>(null)
const errorMsg = ref('')
const password = ref('')
const submitting = ref(false)
const sortedSections = computed(() =>
  [...(share.value?.sections || [])].sort((a, b) => a.order - b.order),
)

const exportingPdf = ref(false)
async function onExportPdf() {
  if (exportingPdf.value) return
  exportingPdf.value = true
  try {
    const { data } = await exportPublicPdf(token.value, password.value || undefined)
    if (data?.code === 0 && data.data?.signedUrl) {
      ElMessage.success('PDF 已生成，正在下载')
      window.open(data.data.signedUrl, '_blank', 'noopener')
    } else {
      ElMessage.error(data?.message || 'PDF 导出失败')
    }
  } finally {
    exportingPdf.value = false
  }
}

async function load() {
  phase.value = 'loading'
  errorMsg.value = ''
  try {
    const { data } = await previewPublicShare(token.value)
    if (data?.code === 0 && data.data) {
      share.value = data.data
      if (data.data.scope === 'password') {
        phase.value = 'password'
      } else {
        // public scope：直接拉全文
        await doAccess()
      }
    } else {
      phase.value = 'error'
      errorMsg.value = data?.message || '链接无效'
    }
  } catch (e: any) {
    phase.value = 'error'
    errorMsg.value = e?.response?.data?.message || '链接无法访问'
  }
}

async function doAccess() {
  try {
    const { data } = await accessPublicShare(token.value)
    if (data?.code === 0 && data.data) {
      share.value = data.data
      phase.value = 'content'
    } else {
      phase.value = 'error'
      errorMsg.value = data?.message || '获取内容失败'
    }
  } catch (e: any) {
    phase.value = 'error'
    errorMsg.value = e?.response?.data?.message || '获取内容失败'
  }
}

async function onSubmitPassword() {
  if (!password.value) {
    ElMessage.warning('请输入密码')
    return
  }
  submitting.value = true
  try {
    const { data } = await verifyPublicShare(token.value, { password: password.value })
    if (data?.code === 0 && data.data) {
      share.value = data.data
      phase.value = 'content'
    } else {
      ElMessage.error(data?.message || '密码错误')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '验证失败')
  } finally {
    submitting.value = false
  }
}

function formatTime(s?: string) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <main class="ps">
    <!-- 顶部品牌条 -->
    <header class="ps__brand">
      <span class="ps__logo">⏳</span>
      <span class="ps__brandText">Moment Weaver · 分享阅读</span>
    </header>

    <div v-loading="phase === 'loading'" class="ps__container">
      <!-- 错误态 -->
      <el-result
        v-if="phase === 'error'"
        icon="error"
        title="无法访问"
        :sub-title="errorMsg"
      >
        <template #extra>
          <el-button type="primary" @click="load">重试</el-button>
        </template>
      </el-result>

      <!-- 密码门 -->
      <el-card v-else-if="phase === 'password'" shadow="never" class="ps__pwd">
        <h2 class="ps__pwdTitle">🔒 这是一份受密码保护的成稿</h2>
        <p class="muted" v-if="share">
          {{ share.draftTitle || '（未命名成稿）' }}
        </p>
        <p class="muted">
          由 {{ share?.createdByName || '匿名采访者' }} 分享 · 有效期至 {{ formatTime(share?.expiresAt) }}
        </p>
        <el-form @submit.prevent="onSubmitPassword">
          <el-form-item>
            <el-input
              v-model="password"
              type="password"
              show-password
              size="large"
              placeholder="请输入阅读密码"
              maxlength="32"
              @keyup.enter="onSubmitPassword"
            />
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="submitting"
            @click="onSubmitPassword"
            style="width: 100%"
          >
            解锁阅读
          </el-button>
        </el-form>
      </el-card>

      <!-- 正文 -->
      <article v-else-if="phase === 'content' && share" class="ps__body">
        <!-- AI 标识横幅（合规自检 #5：不可关闭） -->
        <el-alert
          v-if="share.hasAiContent"
          :title="share.aiLabel || '本文含 AI 生成内容'"
          type="warning"
          :closable="false"
          show-icon
          class="ps__aiAlert"
        />

        <header class="ps__head">
          <h1>{{ share.draftTitle || '（未命名成稿）' }}</h1>
          <p class="ps__meta">
            由 <strong>{{ share.createdByName || '匿名' }}</strong> 分享
            <span v-if="share.createdAt">· 创建于 {{ formatTime(share.createdAt) }}</span>
            <span v-if="share.expiresAt">· 有效期至 {{ formatTime(share.expiresAt) }}</span>
          </p>
        </header>

        <section
          v-for="s in sortedSections"
          :key="s.sectionId"
          class="ps__sec"
        >
          <div class="ps__secHead">
            <h2>{{ s.sectionTitle }}</h2>
            <ProvenanceBadge
              :provenance="s.provenance"
              :ai-generated="s.aiGenerated"
            />
          </div>
          <div v-if="s.content" class="ps__text">{{ s.content }}</div>
          <div v-else class="ps__empty">（该章节暂无内容）</div>
        </section>

        <footer class="ps__foot">
          <el-button
            v-if="share.allowDownload"
            type="primary"
            plain
            :loading="exportingPdf"
            @click="onExportPdf"
          >导出 PDF</el-button>
          <p class="ps__sign">— 完 —</p>
          <p class="ps__signSub">
            本成稿由 Moment Weaver 创建并分享
            <span v-if="!share.allowCopy">· 已禁止复制</span>
            <span v-if="!share.allowDownload">· 已禁止下载</span>
          </p>
        </footer>
      </article>
    </div>
  </main>
</template>

<style scoped>
.ps {
  min-height: 100vh;
  background: #f5f7fa;
  padding-bottom: 80px;
}
.ps__brand {
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  padding: 12px 24px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.ps__logo { font-size: 22px; }
.ps__brandText { font-size: 14px; color: #4b5563; font-weight: 500; }

.ps__container {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px 16px;
}

.ps__pwd {
  max-width: 480px;
  margin: 64px auto;
}
.ps__pwdTitle { margin: 0 0 8px; font-size: 18px; color: #1f2937; }
.muted { color: #6b7280; font-size: 13px; margin: 0 0 8px; }

.ps__body {
  background: #fff;
  border-radius: 12px;
  padding: 56px 64px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}
.ps__aiAlert { margin-bottom: 24px; }

.ps__head {
  text-align: center;
  padding-bottom: 32px;
  border-bottom: 1px dashed #e5e7eb;
  margin-bottom: 40px;
}
.ps__head h1 {
  margin: 0 0 8px;
  font-size: 32px;
  color: #1f2937;
  font-weight: 700;
  letter-spacing: 1px;
}
.ps__meta { color: #9ca3af; font-size: 12px; margin: 0; }

.ps__sec { margin-bottom: 40px; }
.ps__secHead {
  display: flex; align-items: center; gap: 12px;
  margin-bottom: 16px;
}
.ps__secHead h2 {
  margin: 0; font-size: 20px; color: #1f2937;
  border-left: 4px solid #2563eb; padding-left: 10px;
}
.ps__text {
  color: #374151; font-size: 16px; line-height: 1.9;
  white-space: pre-wrap; word-break: break-word;
  text-align: justify;
  user-select: v-bind('share?.allowCopy ? "text" : "none"');
}
.ps__empty { color: #9ca3af; font-style: italic; }

.ps__foot {
  text-align: center; margin-top: 48px; padding-top: 24px;
  border-top: 1px dashed #e5e7eb;
}
.ps__sign { color: #9ca3af; font-size: 14px; letter-spacing: 4px; margin: 0; }
.ps__signSub { color: #d1d5db; font-size: 12px; margin-top: 8px; }

@media (max-width: 768px) {
  .ps__body { padding: 32px 20px; }
  .ps__head h1 { font-size: 24px; }
}
</style>
