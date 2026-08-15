<script setup lang="ts">
/**
 * 创建家族（M10+ Family Phase 1）。
 * 路由：/families/new
 *
 * <p>副作用：创建者自动成为 admin；user.isFamilyAdmin 被设为 true。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createFamily } from '@/api/family'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  name: '',
  description: '',
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入家族名', trigger: 'blur' },
    { min: 1, max: 64, message: '1-64 字', trigger: 'blur' },
  ],
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const { data } = await createFamily({
      name: form.name.trim(),
      description: form.description.trim() || undefined,
    })
    if (data?.code === 0 && data.data) {
      ElMessage.success('家族已创建')
      // 刷新本地 user 状态（isFamilyAdmin → true）
      await auth.fetchMe()
      router.replace(`/families/${data.data.id}`)
    } else {
      ElMessage.error(data?.message || '创建失败')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="fc">
    <header class="fc__head">
      <el-button text @click="router.back()">← 返回</el-button>
      <h2>创建家族</h2>
      <p class="muted">
        创建后您自动成为家族管理员。后续可邀请家人加入并创建家族项目。
      </p>
    </header>

    <el-card shadow="never" class="fc__card">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="家族名" prop="name" required>
          <el-input
            v-model="form.name"
            placeholder="如：张家、李家"
            maxlength="64"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="家族简介">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="选填，如：张家口述史整理项目"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>

        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
          <template #title>家族与个人 workspace 并行存在</template>
          您仍可在「我的项目」页面创建个人项目；家族项目则属于整个家族成员共享。
        </el-alert>

        <div class="fc__actions">
          <el-button @click="router.back()">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="onSubmit">
            创建家族
          </el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.fc { max-width: 640px; margin: 0 auto; }
.fc__head { margin-bottom: 16px; }
.fc__head h2 { margin: 8px 0 4px; }
.muted { color: #6b7280; font-size: 13px; margin: 0; }
.fc__card { border-radius: 10px; }
.fc__actions { display: flex; gap: 8px; justify-content: flex-end; }
</style>
