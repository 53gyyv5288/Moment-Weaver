<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { backend, ai } from '@/api/client'

interface ProbeResult {
  status: 'UP' | 'DOWN' | 'PENDING'
  detail?: string
  latencyMs?: number
}

const backendHealthz = ref<ProbeResult>({ status: 'PENDING' })
const backendReadyz = ref<ProbeResult>({ status: 'PENDING' })
const aiHealthz = ref<ProbeResult>({ status: 'PENDING' })
const aiReadyz = ref<ProbeResult>({ status: 'PENDING' })

async function probe(label: ProbeResult, fn: () => Promise<any>): Promise<ProbeResult> {
  const t0 = performance.now()
  try {
    const res = await fn()
    return { status: 'UP', detail: JSON.stringify(res.data), latencyMs: Math.round(performance.now() - t0) }
  } catch (e: any) {
    return { status: 'DOWN', detail: e?.message ?? String(e), latencyMs: Math.round(performance.now() - t0) }
  }
}

async function run() {
  backendHealthz.value = await probe(backendHealthz.value, () => backend.get('/v1/healthz'))
  backendReadyz.value = await probe(backendReadyz.value, () => backend.get('/v1/readyz'))
  aiHealthz.value = await probe(aiHealthz.value, () => ai.get('/healthz'))
  aiReadyz.value = await probe(aiReadyz.value, () => ai.get('/readyz'))
}

onMounted(run)
</script>

<template>
  <main class="page">
    <el-page-header content="三件套连通性自检" @back="$router.push('/')" />

    <el-card style="margin-top: 16px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <strong>Spring Boot BFF（:8080）</strong>
          <el-button size="small" @click="run">重新检测</el-button>
        </div>
      </template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="GET /v1/healthz">
          <el-tag :type="backendHealthz.status === 'UP' ? 'success' : 'danger'">
            {{ backendHealthz.status }}
          </el-tag>
          <span style="margin-left: 8px; color: #6b7280; font-size: 12px">
            {{ backendHealthz.latencyMs }}ms · {{ backendHealthz.detail }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="GET /v1/readyz">
          <el-tag :type="backendReadyz.status === 'UP' ? 'success' : 'danger'">
            {{ backendReadyz.status }}
          </el-tag>
          <span style="margin-left: 8px; color: #6b7280; font-size: 12px">
            {{ backendReadyz.latencyMs }}ms · {{ backendReadyz.detail }}
          </span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card style="margin-top: 16px">
      <template #header><strong>FastAPI AI（:8000）</strong></template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="GET /healthz">
          <el-tag :type="aiHealthz.status === 'UP' ? 'success' : 'danger'">
            {{ aiHealthz.status }}
          </el-tag>
          <span style="margin-left: 8px; color: #6b7280; font-size: 12px">
            {{ aiHealthz.latencyMs }}ms · {{ aiHealthz.detail }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="GET /readyz">
          <el-tag :type="aiReadyz.status === 'UP' ? 'success' : 'danger'">
            {{ aiReadyz.status }}
          </el-tag>
          <span style="margin-left: 8px; color: #6b7280; font-size: 12px">
            {{ aiReadyz.latencyMs }}ms · {{ aiReadyz.detail }}
          </span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </main>
</template>

<style scoped>
.page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 16px;
}
</style>
