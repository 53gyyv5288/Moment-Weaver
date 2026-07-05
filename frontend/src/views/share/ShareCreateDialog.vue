<script setup lang="ts">
/**
 * 分享创建弹窗（M5-A）。
 * 选择已发布成稿 → 配置 scope/有效期/权限 → 提交 → 后端生成 token + URL
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Link } from '@element-plus/icons-vue'
import { createShare, type ShareLinkVO } from '@/api/share'
import type { NarrativeDraftVO } from '@/types/api'

const props = defineProps<{
  modelValue: boolean
  projectId: string | number
  drafts: NarrativeDraftVO[]
  defaultDraftId?: string | number
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'created', share: ShareLinkVO): void
}>()

const form = ref({
  draftId: undefined as string | number | undefined,
  scope: 'public' as 'public' | 'password',
  password: '',
  expiresInDays: 30,
  allowCopy: true,
  allowDownload: false,
})
const submitting = ref(false)
const result = ref<ShareLinkVO | null>(null)

const draftOptions = computed(() =>
  props.drafts.map(d => ({ label: d.title || `成稿 #${d.id}`, value: d.id })),
)

const show = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      result.value = null
      form.value = {
        draftId: props.defaultDraftId ?? props.drafts[0]?.id,
        scope: 'public',
        password: '',
        expiresInDays: 30,
        allowCopy: true,
        allowDownload: false,
      }
    }
  },
)

async function onSubmit() {
  if (!form.value.draftId) {
    ElMessage.warning('请选择成稿')
    return
  }
  if (form.value.scope === 'password') {
    if (!form.value.password || form.value.password.length < 4 || form.value.password.length > 32) {
      ElMessage.warning('密码长度需在 4-32 字符之间')
      return
    }
  }
  if (form.value.expiresInDays < 1 || form.value.expiresInDays > 90) {
    ElMessage.warning('有效期需在 1-90 天之间')
    return
  }
  submitting.value = true
  try {
    const { data } = await createShare(props.projectId, {
      draftId: form.value.draftId,
      scope: form.value.scope,
      password: form.value.scope === 'password' ? form.value.password : undefined,
      expiresInDays: form.value.expiresInDays,
      allowCopy: form.value.allowCopy,
      allowDownload: form.value.allowDownload,
    })
    if (data?.code === 0 && data.data) {
      result.value = data.data
      emit('created', data.data)
      ElMessage.success('分享链接已生成')
    } else {
      ElMessage.error(data?.message || '创建失败')
    }
  } finally {
    submitting.value = false
  }
}

function onCopyUrl() {
  if (!result.value?.shareUrl) return
  navigator.clipboard.writeText(result.value.shareUrl)
    .then(() => ElMessage.success('已复制'))
    .catch(() => ElMessage.warning('复制失败'))
}

function onClose() {
  show.value = false
}
</script>

<template>
  <el-dialog
    v-model="show"
    :title="result ? '分享已创建' : '新建分享'"
    width="520px"
    :close-on-click-modal="false"
    @close="onClose"
  >
    <!-- 创建结果 -->
    <div v-if="result" class="sc__result">
      <el-result icon="success" title="链接已生成" sub-title="请将以下链接发送给阅读者">
        <template #icon>
          <el-icon :size="48" color="#67c23a"><Link /></el-icon>
        </template>
      </el-result>
      <div class="sc__urlBox">
        <code class="sc__url">{{ result.shareUrl }}</code>
        <el-button type="primary" @click="onCopyUrl">复制链接</el-button>
      </div>
      <el-descriptions :column="1" size="small" border>
        <el-descriptions-item label="成稿">{{ result.draftTitle }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ result.scope === 'password' ? '🔒 密码保护' : '🌐 公开' }}
        </el-descriptions-item>
        <el-descriptions-item label="有效期至">{{ result.expiresAt }}</el-descriptions-item>
      </el-descriptions>
      <p class="sc__hint">
        ⚠ 链接中包含访问凭据，请妥善保管；可随时在分享管理页撤销。
      </p>
    </div>

    <!-- 创建表单 -->
    <el-form v-else label-width="80px" :disabled="submitting">
      <el-form-item label="选择成稿" required>
        <el-select v-model="form.draftId" placeholder="请选择已发布的成稿" style="width: 100%">
          <el-option
            v-for="o in draftOptions"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <p v-if="draftOptions.length === 0" class="sc__warn">
          该项目暂无可分享的成稿（仅已发布状态可分享）
        </p>
      </el-form-item>

      <el-form-item label="分享类型">
        <el-radio-group v-model="form.scope">
          <el-radio value="public">🌐 公开链接（任何拿到链接的人可看）</el-radio>
          <el-radio value="password">🔒 密码保护（需要输入密码）</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.scope === 'password'" label="阅读密码" required>
        <el-input
          v-model="form.password"
          type="password"
          show-password
          placeholder="4-32 字符"
          maxlength="32"
        />
      </el-form-item>

      <el-form-item label="有效期">
        <el-input-number v-model="form.expiresInDays" :min="1" :max="90" />
        <span class="sc__unit">天（1-90）</span>
      </el-form-item>

      <el-form-item label="权限">
        <el-checkbox v-model="form.allowCopy">允许复制正文</el-checkbox>
        <el-checkbox v-model="form.allowDownload">允许下载 PDF（M5-A.3 启用）</el-checkbox>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button v-if="!result" @click="onClose">取消</el-button>
      <el-button v-if="!result" type="primary" :loading="submitting" @click="onSubmit">生成链接</el-button>
      <el-button v-else type="primary" @click="onClose">完成</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.sc__result { padding: 0 8px; }
.sc__urlBox {
  display: flex; align-items: center; gap: 8px;
  margin: 16px 0;
  padding: 12px;
  background: #f3f4f6; border-radius: 6px;
}
.sc__url {
  flex: 1; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px; color: #1f2937;
  word-break: break-all;
}
.sc__hint {
  margin: 12px 0 0; color: #f59e0b; font-size: 12px;
  background: #fffbeb; padding: 8px 12px; border-radius: 4px;
}
.sc__warn { color: #f59e0b; font-size: 12px; margin: 4px 0 0; }
.sc__unit { color: #9ca3af; font-size: 12px; margin-left: 8px; }
</style>
