<script setup lang="ts">
/**
 * 采访对话房间。M2 SSE 流式输出。
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getInterviewSession,
  streamInterviewMessage,
  closeInterviewSession,
  type InterviewSessionVO,
  type InterviewMessageVO,
} from '@/api/interview'

const route = useRoute()
const router = useRouter()
const sessionId = computed(() => route.params.id as string)

const session = ref<InterviewSessionVO | null>(null)
const input = ref('')
const streaming = ref(false)
const closing = ref(false)
const scroller = ref<HTMLElement | null>(null)

const visibleMessages = computed(() =>
  (session.value?.messages || []).filter(m => m.role !== 'system'),
)

async function load() {
  const { data } = await getInterviewSession(sessionId.value)
  if (data && data.code === 0) {
    session.value = data.data
    await nextTick()
    scrollToBottom()
  } else {
    ElMessage.error(data?.message || '会话不存在')
    router.replace('/projects')
  }
}

function scrollToBottom() {
  if (!scroller.value) return
  scroller.value.scrollTop = scroller.value.scrollHeight
}

async function onSend() {
  if (!input.value.trim() || streaming.value) return
  const text = input.value.trim()
  input.value = ''

  // 1) 立即把 user 消息塞进 session 用于渲染
  const userMsg: InterviewMessageVO = { role: 'user', content: text, source: 'human' }
  session.value!.messages.push(userMsg)
  // 准备一个空 assistant 消息占位
  const assistantMsg: InterviewMessageVO = { role: 'assistant', content: '', source: 'ai_generated' }
  session.value!.messages.push(assistantMsg)
  await nextTick()
  scrollToBottom()

  streaming.value = true
  try {
    await streamInterviewMessage(sessionId.value, text, {
      onStart: () => {},
      onToken: (token) => {
        assistantMsg.content += token
        // 触发响应式更新（直接 mutate 数组也能更新）
        session.value!.messages = [...session.value!.messages]
        nextTick(scrollToBottom)
      },
      onError: (msg) => {
        ElMessage.error('AI 错误：' + msg)
      },
      onDone: () => {
        // 流结束，server 端已把 assistant 完整内容持久化到 Mongo
      },
    })
  } catch (e: any) {
    ElMessage.error(e?.message || '发送失败')
  } finally {
    streaming.value = false
  }
}

async function onClose() {
  await ElMessageBox.confirm('结束本次采访会话？结束后将无法继续对话。', '确认', {
    type: 'warning',
  }).catch(() => null)
  closing.value = true
  try {
    const { data } = await closeInterviewSession(sessionId.value)
    if (data && data.code === 0) {
      ElMessage.success('已结束')
      router.replace('/projects')
    }
  } finally {
    closing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="ir" v-if="session">
    <header class="ir__head">
      <el-button text @click="router.back()">← 返回</el-button>
      <div class="ir__title">
        <h3>{{ session.subjectDisplayName }} · 采访</h3>
        <p class="muted">项目：{{ session.projectName }}</p>
      </div>
      <el-button :loading="closing" type="danger" plain @click="onClose">结束会话</el-button>
    </header>

    <main ref="scroller" class="ir__main">
      <div v-if="visibleMessages.length === 0" class="ir__empty">
        <p>开始和 AI 采访官对话吧。提一个具体的开场问题，比如：</p>
        <p class="ir__hint">"您是哪一年出生的？老家在哪里？"</p>
      </div>

      <div
        v-for="(m, idx) in visibleMessages"
        :key="idx"
        class="ir__msg"
        :class="`ir__msg--${m.role}`"
      >
        <div class="ir__bubble">
          <div class="ir__meta">
            <span class="ir__role">{{ m.role === 'user' ? '采访者' : 'AI 采访官' }}</span>
            <el-tag v-if="m.source === 'ai_generated'" size="small" type="warning" effect="plain">AI 生成</el-tag>
          </div>
          <div class="ir__content">{{ m.content }}<span v-if="streaming && idx === visibleMessages.length - 1 && m.role === 'assistant'" class="ir__cursor">▌</span></div>
        </div>
      </div>
    </main>

    <footer class="ir__foot">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        :disabled="streaming"
        placeholder="说点什么，按 Ctrl+Enter 发送"
        @keydown.ctrl.enter="onSend"
      />
      <el-button type="primary" :loading="streaming" :disabled="!input.trim()" @click="onSend">
        发送
      </el-button>
    </footer>
  </div>
</template>

<style scoped>
.ir {
  max-width: 820px;
  height: calc(100vh - 100px);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
}
.ir__head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}
.ir__title { flex: 1; }
.ir__title h3 { margin: 0; }
.muted { color: #6b7280; font-size: 12px; margin: 0; }
.ir__main {
  flex: 1;
  overflow-y: auto;
  padding: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ir__empty { color: #6b7280; text-align: center; margin-top: 60px; }
.ir__hint { color: #9ca3af; font-size: 13px; margin-top: 8px; }
.ir__msg { display: flex; }
.ir__msg--user { justify-content: flex-end; }
.ir__msg--assistant { justify-content: flex-start; }
.ir__bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #f3f4f6;
}
.ir__msg--user .ir__bubble {
  background: #2563eb;
  color: #fff;
}
.ir__msg--user .ir__meta { color: rgba(255,255,255,0.85); }
.ir__meta {
  font-size: 11px;
  color: #6b7280;
  margin-bottom: 4px;
  display: flex;
  gap: 6px;
  align-items: center;
}
.ir__role { font-weight: 500; }
.ir__content { white-space: pre-wrap; line-height: 1.6; }
.ir__cursor { animation: blink 1s steps(2) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.ir__foot {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}
.ir__foot :deep(.el-textarea) { flex: 1; }
</style>
