<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  account: '',
  password: '',
})

const rules: FormRules = {
  account: [
    { required: true, message: '请输入手机号或邮箱', trigger: 'blur' },
    { min: 4, max: 128, message: '长度 4-128', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码 8-64 位', trigger: 'blur' },
  ],
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.login(form.account.trim(), form.password)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/projects'
    router.replace(redirect)
  } catch (e: any) {
    // 业务错误由 axios 拦截器统一 toast；这里只需关 loading
    console.error(e)
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
          <h2>登录 Moment Weaver</h2>
          <p>用手机号或邮箱登录</p>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="onSubmit">
        <el-form-item label="账号" prop="account">
          <el-input v-model="form.account" placeholder="手机号 / 邮箱" autocomplete="username" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="至少 8 位"
            autocomplete="current-password"
          />
        </el-form-item>

        <el-button type="primary" :loading="loading" class="auth__submit" @click="onSubmit">
          登录
        </el-button>

        <div class="auth__extra">
          还没有账号？
          <el-link type="primary" @click="router.push('/register')">立即注册</el-link>
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
  max-width: 420px;
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
