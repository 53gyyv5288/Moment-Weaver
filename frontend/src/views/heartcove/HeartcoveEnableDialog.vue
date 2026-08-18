<script setup lang="ts">
/**
 * 心声信箱 · 开启对话框
 *
 * 必须勾选同意《数字人格授权书》才能 enable。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  enableHeartcove,
  getConsentText,
  type HeartcoveConsentText,
} from '@/api/heartcove'

const props = defineProps<{ subjectId: number; subjectName: string }>()
const emit = defineEmits<{
  enabled: []
  cancel: []
}>()

const consent = ref<HeartcoveConsentText | null>(null)
const agreed = ref(false)
const note = ref('')
const submitting = ref(false)

onMounted(async () => {
  try {
    consent.value = await getConsentText()
  } catch (e) {
    ElMessage.error('加载授权书失败')
  }
})

async function onSubmit() {
  if (!agreed.value || !consent.value) {
    ElMessage.warning('请先阅读并同意授权书')
    return
  }
  submitting.value = true
  try {
    await enableHeartcove(props.subjectId, {
      consentVersion: consent.value.version,
      agreed: true,
      note: note.value || undefined,
    })
    ElMessage.success('心声信箱已开启')
    emit('enabled')
  } catch (e: any) {
    ElMessage.error(e?.message || '开启失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="`为「${subjectName}」开启心声信箱`"
    width="640px"
    :show-close="false"
    @close="emit('cancel')"
  >
    <div class="hc-enable">
      <el-alert
        v-if="consent"
        :title="consent.title"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />

      <pre v-if="consent" class="hc-enable__body">{{ consent.body }}</pre>

      <el-form label-position="top">
        <el-form-item>
          <el-input
            v-model="note"
            type="textarea"
            :rows="2"
            placeholder="备注（可选）：签署授权时的想法或顾虑"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="agreed">
            我已阅读并同意上述《数字人格授权书》全部条款
          </el-checkbox>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!agreed"
        @click="onSubmit"
      >同意并开启</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hc-enable__body {
  background: var(--mw-bg);
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  padding: 16px 20px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  color: var(--mw-text-secondary);
  white-space: pre-wrap;
  max-height: 360px;
  overflow-y: auto;
  margin: 0 0 16px;
}
</style>