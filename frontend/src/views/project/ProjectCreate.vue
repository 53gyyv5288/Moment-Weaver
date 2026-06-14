<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createProject } from '@/api/project'
import type { ProjectType } from '@/api/project'

const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive<{ type: ProjectType; name: string; description: string }>({
  type: 'family',
  name: '',
  description: '',
})

const rules: FormRules = {
  type: [{ required: true, message: '请选择项目类型', trigger: 'change' }],
  name: [
    { required: true, message: '请输入项目名', trigger: 'blur' },
    { min: 1, max: 128, message: '1-128 字', trigger: 'blur' },
  ],
  description: [{ max: 512, message: '最多 512 字', trigger: 'blur' }],
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const { data } = await createProject({
      type: form.type,
      name: form.name.trim(),
      description: form.description.trim() || undefined,
    })
    if (data && data.code === 0) {
      ElMessage.success('项目已创建（M2 阶段可进入详情）')
      router.replace('/projects')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="create">
    <el-card shadow="never">
      <template #header>
        <h3 style="margin: 0">新建项目</h3>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="项目类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio-button value="family">家族</el-radio-button>
            <el-radio-button value="personal">个人</el-radio-button>
          </el-radio-group>
          <div class="form__hint">
            <template v-if="form.type === 'family'">
              家族项目：可录入多位被采访者，后续需邀请他们授权（M2）。
            </template>
            <template v-else>
              个人项目：时光胶囊，只录入自己。
            </template>
          </div>
        </el-form-item>

        <el-form-item label="项目名" prop="name">
          <el-input v-model="form.name" placeholder="比如：爸爸的知青岁月 / 我的 2025" />
        </el-form-item>

        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="一句话介绍这个项目（选填）"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="onSubmit">创建</el-button>
          <el-button @click="router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert class="create__hint" type="info" :closable="false" show-icon>
      <template #title>关于授权</template>
      <p>M1 创建项目后不需要立刻邀请被采访者。</p>
      <p>M2 阶段在「项目详情 → 人物」中录入被采访者，并生成授权链接 / 二维码。被采访者点击后须先阅读知情同意书并同意，AI 才会向其提问。</p>
    </el-alert>
  </div>
</template>

<style scoped>
.create {
  max-width: 720px;
  margin: 0 auto;
}
.create__hint {
  margin-top: 16px;
}
.create__hint p {
  margin: 4px 0;
}
.form__hint {
  margin-top: 6px;
  font-size: 12px;
  color: #9ca3af;
}
</style>
