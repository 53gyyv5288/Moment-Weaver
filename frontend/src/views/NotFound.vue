<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <main class="nf">
    <div class="nf__box">
      <h1>404</h1>
      <p>页面不存在，或者已经被遗忘到「合规删除」里去了。</p>
      <el-button type="primary" @click="router.replace('/')">回首页</el-button>
    </div>
  </main>
</template>

<style scoped>
.nf {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.nf__box {
  text-align: center;
}
.nf__box h1 {
  font-size: 96px;
  margin: 0;
  color: #1f2937;
}
.nf__box p {
  color: #6b7280;
  margin: 8px 0 24px;
}
</style>
