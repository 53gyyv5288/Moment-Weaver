<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createProject } from '@/api/project'
import type { ProjectType } from '@/api/project'
import { listFamilies, type FamilyVO } from '@/api/family'

const router = useRouter()
const route = useRoute()
const formRef = ref<FormInstance>()
const submitting = ref(false)

interface ProjectCreateForm {
  type: ProjectType
  name: string
  description: string
  /** 必选 family.id（不允许个人项目了） */
  familyId: string | number
}

const form = reactive<ProjectCreateForm>({
  type: 'family',
  name: '',
  description: '',
  familyId: '',
})

const families = ref<FamilyVO[]>([])
const eligibleFamilies = computed(() => {
  // 只有 admin/editor 能创建家族项目（viewer 不可见创建入口；后端会兜底校验）
  return families.value.filter((f) => f.myRole === 'admin' || f.myRole === 'editor')
})

const rules: FormRules = {
  type: [{ required: true, message: '请选择项目类型', trigger: 'change' }],
  familyId: [{ required: true, message: '请选择家族', trigger: 'change' }],
  name: [
    { required: true, message: '请输入项目名', trigger: 'blur' },
    { min: 1, max: 128, message: '1-128 字', trigger: 'blur' },
  ],
  description: [{ max: 512, message: '最多 512 字', trigger: 'blur' }],
}

async function loadFamilies() {
  const { data } = await listFamilies()
  if (data?.code === 0) families.value = data.data || []
  // 如果 URL 带 familyId query，自动选中（从家族详情页跳过来的场景）
  const qFamilyId = route.query.familyId as string | undefined
  if (qFamilyId) {
    form.familyId = qFamilyId
  } else if (eligibleFamilies.value.length > 0) {
    // 默认选中第一个可用家族
    form.familyId = eligibleFamilies.value[0].id
  }
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 双重防御：未选家族直接拦下
  if (!form.familyId) {
    ElMessage.error('请先加入一个家族')
    return
  }
  submitting.value = true
  try {
    const { data } = await createProject({
      type: form.type,
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      familyId: form.familyId,
    })
    if (data && data.code === 0) {
      ElMessage.success('项目已创建')
      router.replace(`/families/${form.familyId}`)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(loadFamilies)
</script>

<template>
  <div class="create">
    <el-card shadow="never">
      <template #header>
        <h3 style="margin: 0">新建项目</h3>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="所属范围" prop="familyId">
          <el-radio-group v-model="form.familyId">
            <el-radio-button
              v-for="f in eligibleFamilies"
              :key="f.id"
              :value="f.id"
            >
              {{ f.name }}
            </el-radio-button>
          </el-radio-group>
          <div class="form__hint">
            <strong style="color: #d97706">家族项目：</strong>
            所有家族成员都能查看，{{ form.type === 'family' ? '可用于家族小传成稿' : '仅做个人时光胶囊' }}
          </div>
          <div v-if="families.length === 0" class="form__hint">
            您还没有加入任何家族。
            <el-link type="primary" @click="router.push('/families/new')">立即创建一个</el-link>
          </div>
          <div v-else-if="eligibleFamilies.length === 0" class="form__hint">
            您所在的家族没有可创建项目的权限（仅家族 admin / editor 可创建项目）。
          </div>
        </el-form-item>

        <el-form-item label="项目类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio-button value="family">家族小传</el-radio-button>
            <el-radio-button value="personal">个人小传</el-radio-button>
          </el-radio-group>
          <div class="form__hint">
            <template v-if="form.type === 'family'">
              可录入多位被采访者，适合多人协作；模板生成「家族小传」。
            </template>
            <template v-else>
              仅录入自己；模板生成「人物小传」。
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
  </div>
</template>

<style scoped>
.create {
  max-width: 720px;
  margin: 0 auto;
}
.form__hint {
  margin-top: 6px;
  font-size: 12px;
  color: #9ca3af;
}
</style>
