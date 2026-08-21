<script setup lang="ts">
/**
 * 强制改密页（M10+ Family Phase 1）。
 *
 * <p>触发场景：
 *   <ul>
 *     <li>管理员创建的账号首次登录 → 后端返回 mustChangePassword=true</li>
 *     <li>管理员重置了某成员的密码 → 标记强制改密</li>
 *   </ul>
 *
 * <p>路由：/change-password（公开路由，但需要登录；Layout 不显示）
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { changePassword } from '@/api/auth'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirm: '',
})

const rules: FormRules = {
  oldPassword: [
    { required: true, message: '请输入旧密码', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码 8-64 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_r, v, cb) => {
        if (v !== form.newPassword) return cb(new Error('两次密码不一致'))
        cb()
      },
      trigger: 'blur',
    },
  ],
}

async function onSubmit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (form.newPassword === form.oldPassword) {
    ElMessage.warning('新密码不能与旧密码相同')
    return
  }
  loading.value = true
  try {
    await changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword,
    })
    ElMessage.success('密码已修改')
    // 从后端拉最新 user，fetchMe 内部会调 setUser 同步 Pinia + localStorage
    // 直接改本地字段不够：刷新后守卫从 stale localStorage 命中会被劫回改密页
    await auth.fetchMe()
    router.replace('/projects')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '修改失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="cp">
    <el-card class="cp__card" shadow="always">
      <template #header>
        <div class="cp__head">
          <h2>修改密码</h2>
          <p>首次登录 / 密码被重置后，请设置一个新密码</p>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="旧密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            placeholder="管理员告知您的初始密码"
            autocomplete="current-password"
          />
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            placeholder="8-64 位，建议字母+数字"
            autocomplete="new-password"
          />
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirm">
          <el-input
            v-model="form.confirm"
            type="password"
            show-password
            placeholder="再输一次"
            autocomplete="new-password"
          />
        </el-form-item>

        <el-button
          type="primary"
          :loading="loading"
          class="cp__submit"
          @click="onSubmit"
        >
          修改密码并进入
        </el-button>
      </el-form>
    </el-card>
  </main>
</template>

<style scoped>
.cp {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f0f9ff 0%, #e0e7ff 100%);
  padding: 24px;
}
.cp__card {
  width: 100%;
  max-width: 460px;
  border-radius: 12px;
}
.cp__head h2 {
  margin: 0 0 4px;
  font-size: 20px;
}
.cp__head p {
  margin: 0;
  font-size: 13px;
  color: #9ca3af;
}
.cp__submit {
  width: 100%;
  margin-top: 8px;
}
</style>
