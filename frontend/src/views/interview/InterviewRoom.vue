<script setup lang="ts">
/**
 * 采访对话房间。M2 SSE 流式输出 + M3 摘要按钮 + M5+ 思考链面板。
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
import { summarizeSession } from '@/api/summary'

const route = useRoute()
const router = useRouter()
const sessionId = computed(() => route.params.id as string)

const session = ref<InterviewSessionVO | null>(null)
const input = ref('')
const streaming = ref(false)
const closing = ref(false)
const regenerating = ref(false)
const scroller = ref<HTMLElement | null>(null)

// 思考链面板：实时累计当前流的 thinking，并控制哪些 idx 处于展开状态。
// 流进行中：自动展开当前消息的面板；流结束：自动折叠。
const liveThinking = ref('')
const activeCollapse = ref<string[]>([])

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

function panelKey(idx: number): string {
  // 每条 AI 消息面板的稳定 key（用 idx 即可：本组件内消息列表顺序稳定）
  return `think-${idx}`
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

  // 2) 流式开始：记录当前 assistant 在 visibleMessages 里的位置
  const assistantIdx = visibleMessages.value.length - 1
  const panelId = panelKey(assistantIdx)
  liveThinking.value = ''

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
      onThinking: (token) => {
        liveThinking.value += token
        // 流进行中：自动展开思考面板，让用户看到 AI 正在想什么
        if (!activeCollapse.value.includes(panelId)) {
          activeCollapse.value = [...activeCollapse.value, panelId]
        }
        nextTick(scrollToBottom)
      },
      onError: (msg) => {
        ElMessage.error('AI 错误：' + msg)
      },
      onDone: () => {
        // 流结束：把 live thinking 落到 assistant 消息上，再收起面板
        if (liveThinking.value) {
          assistantMsg.thinking = liveThinking.value
          session.value!.messages = [...session.value!.messages]
        }
        liveThinking.value = ''
        activeCollapse.value = activeCollapse.value.filter(k => k !== panelId)
      },
    })
  } catch (e: any) {
    ElMessage.error(e?.message || '发送失败')
    // 异常路径：同样清理面板状态
    liveThinking.value = ''
    activeCollapse.value = activeCollapse.value.filter(k => k !== panelId)
  } finally {
    streaming.value = false
  }
}

async function onClose() {
  // 1) 确认弹窗：用户取消就早退，**不再调 API**
  try {
    await ElMessageBox.confirm('结束本次采访会话？结束后将无法继续对话。', '确认', {
      type: 'warning',
    })
  } catch {
    return
  }

  // 2) 调关闭接口：明确处理成功 / 业务失败 / 网络异常三种情况
  closing.value = true
  try {
    const { data } = await closeInterviewSession(sessionId.value)
    if (data?.code === 0) {
      ElMessage.success('已结束，AI 正在生成摘要…')
      // 跳到摘要页，让用户看到异步生成的摘要
      router.replace(`/interview/${sessionId.value}/summary`)
      return
    }
    // 后端 200 但业务码非 0 —— 兜底提示
    ElMessage.error(data?.message || '结束失败')
  } catch (e: any) {
    // 网络错误 / 超时（status 是 undefined，拦截器不提示） / 4xx5xx
    const msg = e?.response?.data?.message || e?.message || '结束失败，请稍后再试'
    ElMessage.error(msg)
  } finally {
    closing.value = false
  }
}

async function onShowSummary() {
  // 没关采访也能看摘要（已生成的）
  router.push(`/interview/${sessionId.value}/summary`)
}

async function onGenerateSummary() {
  // 手动触发同步摘要生成
  regenerating.value = true
  try {
    const { data } = await summarizeSession(sessionId.value)
    if (data?.code === 0) {
      ElMessage.success('摘要已生成')
      router.push(`/interview/${sessionId.value}/summary`)
    } else {
      ElMessage.error(data?.message || '生成失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '生成失败')
  } finally {
    regenerating.value = false
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
      <el-button v-if="session.summary" plain @click="onShowSummary">查看摘要</el-button>
      <el-button v-else type="primary" plain :loading="regenerating" @click="onGenerateSummary">生成摘要</el-button>
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
          <!-- 思考链面板：仅 assistant 消息 + 有内容时渲染 -->
          <el-collapse
            v-if="m.role === 'assistant' && (m.thinking || (streaming && idx === visibleMessages.length - 1 && liveThinking))"
            v-model="activeCollapse"
            :key="`${idx}-${streaming}`"
            class="ir__think"
          >
            <el-collapse-item :name="panelKey(idx)">
              <template #title>
                <span class="ir__think-title">
                  <span class="ir__think-icon">💭</span>
                  <span class="ir__think-label">AI 思考</span>
                  <span v-if="streaming && idx === visibleMessages.length - 1" class="ir__think-status">
                    正在思考…
                  </span>
                  <span v-else class="ir__think-count">
                    {{ (m.thinking || '').length }} 字
                  </span>
                </span>
              </template>
              <div class="ir__think-body">
                {{ idx === visibleMessages.length - 1 && streaming ? liveThinking : m.thinking }}
              </div>
            </el-collapse-item>
          </el-collapse>

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

/* 思考链面板：轻灰背景 + 斜体 + 等宽观感 */
.ir__think {
  margin-bottom: 8px;
  border: 1px solid var(--mw-border, #e5e7eb);
  border-radius: 6px;
  background: #f9fafb;
}
.ir__think :deep(.el-collapse-item__header) {
  border-bottom: none;
  padding-left: 10px;
  min-height: 32px;
  font-size: 12px;
  color: #6b7280;
}
.ir__think :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}
.ir__think :deep(.el-collapse-item__content) {
  padding: 0 10px 10px 10px;
}
.ir__think-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.ir__think-icon { font-size: 13px; }
.ir__think-label { font-weight: 500; }
.ir__think-status {
  color: var(--mw-primary, #d97706);
  font-size: 11px;
}
.ir__think-count {
  color: #9ca3af;
  font-size: 11px;
  margin-left: 4px;
}
.ir__think-body {
  font-style: italic;
  color: #4b5563;
  white-space: pre-wrap;
  line-height: 1.5;
  max-height: 260px;
  overflow-y: auto;
  font-size: 13px;
  border-left: 3px solid #d1d5db;
  padding-left: 8px;
  background: #f3f4f6;
  border-radius: 0 4px 4px 0;
  padding-top: 6px;
  padding-bottom: 6px;
}
</style>