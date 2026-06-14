<script setup lang="ts">
/**
 * 公开授权页（无 JWT）。被采访者点开链接后看到的内容。
 * 路由：/authz/:token
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import consentMd from '@/assets/consent.md?raw'
import {
  viewPublicAuthorization,
  grantPublicAuthorization,
  denyPublicAuthorization,
  type AuthorizationVO,
} from '@/api/authorization'

const route = useRoute()
const router = useRouter()
const token = computed(() => route.params.token as string)

const authz = ref<AuthorizationVO | null>(null)
const loading = ref(true)
const submitting = ref(false)
const agreed = ref(false)
const denyReason = ref('')
const showDenyDialog = ref(false)

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const consentHtml = computed(() => md.render(consentMd))

async function load() {
  loading.value = true
  try {
    const { data } = await viewPublicAuthorization(token.value)
    if (data && data.code === 0 && data.data) {
      authz.value = data.data
    } else {
      ElMessage.error(data?.message || '链接无效')
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function onGrant() {
  if (!agreed.value) {
    ElMessage.warning('请先勾选同意条款')
    return
  }
  submitting.value = true
  try {
    const { data } = await grantPublicAuthorization(token.value)
    if (data && data.code === 0) {
      ElMessage.success('已同意，您可关闭此页面')
      authz.value = data.data!
    } else {
      ElMessage.error(data?.message || '操作失败')
    }
  } finally {
    submitting.value = false
  }
}

async function onDenyConfirm() {
  if (!denyReason.value.trim()) {
    ElMessage.warning('请简单填写拒绝原因')
    return
  }
  submitting.value = true
  try {
    const { data } = await denyPublicAuthorization(token.value)
    if (data && data.code === 0) {
      showDenyDialog.value = false
      ElMessage.success('已拒绝，您可关闭此页面')
      authz.value = data.data!
    } else {
      ElMessage.error(data?.message || '操作失败')
    }
  } finally {
    submitting.value = false
  }
}

function statusLabel(s?: string) {
  return { pending: '待您处理', granted: '已同意', denied: '已拒绝', revoked: '已被采访者撤销', expired: '链接已过期' }[s || ''] || s
}
</script>

<template>
  <main class="consent" v-loading="loading">
    <header class="consent__head">
      <h1>Moment Weaver · 知情同意书</h1>
      <p class="muted">此页面无需登录；您填写的所有信息将仅用于本次采访。</p>
    </header>

    <el-card v-if="authz" shadow="never" class="consent__ctx">
      <el-descriptions :column="2" size="small" border>
        <el-descriptions-item label="关联项目">{{ authz.scopes.length }} 项授权</el-descriptions-item>
        <el-descriptions-item label="同意书版本">{{ authz.consentVersion }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="authz.status === 'pending' ? 'warning' : 'info'">{{ statusLabel(authz.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="链接有效期">{{ authz.expiresAt }}</el-descriptions-item>
      </el-descriptions>

      <p class="scopes">
        <strong>采访者请求您授权：</strong>
        <el-tag v-for="sc in authz.scopes" :key="sc" size="small" effect="plain">
          {{
            {
              interview: 'AI 采访对话',
              narrative: 'AI 撰写成稿',
              asset: '使用上传素材',
              share: '同意被分享',
            }[sc] || sc
          }}
        </el-tag>
      </p>
    </el-card>

    <article class="consent__body" v-html="consentHtml" />

    <footer v-if="authz?.status === 'pending'" class="consent__foot">
      <el-checkbox v-model="agreed" size="large">
        我已阅读并理解上述全部条款，自愿同意。
      </el-checkbox>
      <div class="consent__btns">
        <el-button type="danger" plain :loading="submitting" @click="showDenyDialog = true">
          拒绝
        </el-button>
        <el-button type="primary" :loading="submitting" :disabled="!agreed" @click="onGrant">
          我已阅读并同意
        </el-button>
      </div>
    </footer>

    <footer v-else class="consent__foot">
      <el-alert :title="`当前状态：${statusLabel(authz?.status)}`" type="info" :closable="false" show-icon />
    </footer>

    <el-dialog v-model="showDenyDialog" title="确认拒绝" width="420px">
      <p>您可以选择告诉采访者一个简短原因（仅您与采访者可见）：</p>
      <el-input v-model="denyReason" type="textarea" :rows="3" placeholder="选填" />
      <template #footer>
        <el-button @click="showDenyDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="onDenyConfirm">确认拒绝</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.consent {
  max-width: 820px;
  margin: 0 auto;
  padding: 32px 16px 80px;
}
.consent__head h1 {
  margin: 0 0 4px;
  font-size: 24px;
  color: #1f2937;
}
.muted { color: #6b7280; margin: 0 0 16px; }
.consent__ctx { margin-bottom: 16px; }
.scopes { margin: 12px 0 0; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.consent__body {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 24px 32px;
  line-height: 1.7;
  color: #1f2937;
}
.consent__body :deep(h1) { font-size: 22px; margin-top: 0; }
.consent__body :deep(h2) { font-size: 18px; margin-top: 28px; }
.consent__body :deep(ul) { padding-left: 24px; }
.consent__body :deep(blockquote) {
  border-left: 3px solid #d1d5db;
  margin: 0;
  padding: 4px 12px;
  color: #6b7280;
  background: #f9fafb;
}
.consent__foot {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.consent__btns {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}
</style>
