<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  account: '',
  password: '',
  confirm: '',
  displayName: '',
})

const isEmail = (s: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)
const isPhone = (s: string) => /^1[3-9]\d{9}$/.test(s)

const rules: FormRules = {
  account: [
    { required: true, message: '请输入手机号或邮箱', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (!v) return cb()
        if (!isEmail(v) && !isPhone(v)) return cb(new Error('格式不正确（手机号或邮箱）'))
        cb()
      },
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码 8-64 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v !== form.password) return cb(new Error('两次密码不一致'))
        cb()
      },
      trigger: 'blur',
    },
  ],
  displayName: [
    { required: true, message: '请输入显示名', trigger: 'blur' },
    { min: 1, max: 64, message: '1-64 字', trigger: 'blur' },
  ],
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.register(form.account.trim(), form.password, form.displayName.trim())
    ElMessage.success('注册成功，已为你创建工作区')
    router.replace('/projects')
  } catch (e: any) {
    console.error(e)
    ElMessage.error(e?.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth">
    <el-card class="auth__card" shadow="always">
      <template #header>
        <div class="auth__head">
          <h2>注册 Moment Weaver</h2>
          <p>注册即默认创建一个工作区</p>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="onSubmit">
        <el-form-item label="账号（手机号或邮箱）" prop="account">
          <el-input v-model="form.account" placeholder="13800138000 或 you@example.com" />
        </el-form-item>

        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="form.displayName" placeholder="比如：老王 / Lily" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" show-password placeholder="再输一次" />
        </el-form-item>

        <el-button type="primary" :loading="loading" class="auth__submit" @click="onSubmit">
          注册
        </el-button>

        <div class="auth__extra">
          已有账号？
          <el-link type="primary" @click="router.push('/login')">去登录</el-link>
        </div>
      </el-form>
    </el-card>
  </main>
</template>

<style scoped>
.auth {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f0f9ff 0%, #e0e7ff 100%);
  padding: 24px;
}
.auth__card {
  width: 100%;
  max-width: 460px;
  border-radius: 12px;
}
.auth__head h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.auth__head p {
  margin: 0;
  font-size: 13px;
  color: #9ca3af;
}
.auth__submit {
  width: 100%;
  margin-top: 8px;
}
.auth__extra {
  text-align: center;
  margin-top: 16px;
  font-size: 13px;
  color: #6b7280;
}
</style>
