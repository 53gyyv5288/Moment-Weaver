<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="home">
    <el-container>
      <el-header>
        <h1>Moment Weaver</h1>
        <p class="subtitle">时光编织者 · AI 引导式故事采集与生成</p>
      </el-header>

      <el-main>
        <el-space direction="vertical" size="large" style="width: 100%">
          <el-alert type="success" :closable="false" show-icon>
            <template #title>M0 启动成功</template>
            三件套已联通：Spring Boot（8080）+ FastAPI（8000）+ Vue Vite（5173）
          </el-alert>

          <el-card>
            <template #header><strong>下一步</strong></template>
            <el-space direction="vertical">
              <p>👉 <el-link type="primary" @click="router.push('/health-check')">点击这里跑三件套连通性自检</el-link></p>
              <p>👉 M1 阶段：账号/工作区/项目骨架（账号密码注册 → 登录 → 建项目）</p>
              <p>👉 M2 阶段：人物 / 授权 / AI 采访</p>
            </el-space>
          </el-card>

          <el-card>
            <template #header><strong>项目范围（v1.1 瘦版）</strong></template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="项目类型">family（家族）/ personal（个人）</el-descriptions-item>
              <el-descriptions-item label="采访方式">仅文本（语音/ASR 二期）</el-descriptions-item>
              <el-descriptions-item label="成稿模板">2 个：人物小传 / 家族小传</el-descriptions-item>
              <el-descriptions-item label="后端架构">Spring Boot 3 模块化单体</el-descriptions-item>
              <el-descriptions-item label="AI 架构">FastAPI 单应用</el-descriptions-item>
              <el-descriptions-item label="数据库">MySQL 8.0 + MongoDB 6.0 + Redis 6+</el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-space>
      </el-main>
    </el-container>
  </main>
</template>

<style scoped>
.home {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 16px;
}
h1 {
  margin: 0;
  font-size: 28px;
}
.subtitle {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 14px;
}
</style>
