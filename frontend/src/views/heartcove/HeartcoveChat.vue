<script setup lang="ts">
/**
 * 心声信箱 · 对话页
 *
 * 设计原则：
 *   1. 独立视觉：墨色 / 米白 / 思源宋体 / 大留白 / 慢动画
 *   2. 顶部固定 AI 标识 banner（合规底线，不可关闭）
 *   3. 输入框"温柔"——回车发送，shift+回车换行
 *   4. 流式接收 SSE，逐 token 渲染打字机效果
 *   5. 每条 AI 消息下方有"AI 生成"徽标
 */
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  closeHeartcoveSession,
  openHeartcoveSession,
  streamHeartcoveChat,
  type HeartcoveMessageVO,
  type HeartcoveSessionVO,
} from '@/api/heartcove'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const subjectId = computed(() => String(route.params.subjectId))

const session = ref<HeartcoveSessionVO | null>(null)
const inputText = ref('')
const streaming = ref(false)
const abortRef = ref<AbortController | null>(null)
const scrollRef = ref<HTMLDivElement | null>(null)

// 临时缓冲：流式追加的 token 拼到这一条
const streamingMsg = ref<HeartcoveMessageVO | null>(null)

async function loadOrCreate() {
  try {
    const s = await openHeartcoveSession(subjectId.value)
    session.value = s
    scrollToBottom()
  } catch (e: any) {
    ElMessage.error(e?.message || '打开会话失败')
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    }
  })
}

async function send() {
  const txt = inputText.value.trim()
  if (!txt || streaming.value || !session.value) return
  inputText.value = ''

  // 1) 立即在本地插一条 user 消息
  const now = new Date().toISOString()
  const userMsg = reactive<HeartcoveMessageVO>({
    id: '',
    role: 'user',
    content: txt,
    createdAt: now,
  })
  session.value.messages.push(userMsg)

  // 2) 创建占位 AI 消息（用 reactive 包装，避免「深对象属性写入不触发响应式更新」的 Vue 3 边界）
  const aiMsg = reactive<HeartcoveMessageVO>({
    id: '',
    role: 'ai',
    content: '',
    createdAt: now,
  })
  session.value.messages.push(aiMsg)
  streamingMsg.value = aiMsg
  scrollToBottom()

  streaming.value = true
  const controller = new AbortController()
  abortRef.value = controller

  try {
    await streamHeartcoveChat(
      session.value.id,
      txt,
      {
        onToken: (tok) => {
          aiMsg.content += tok
          scrollToBottom()
        },
        onMeta: (meta) => {
          if (meta.unknownType) aiMsg.unknownType = meta.unknownType
          if (meta.sourceQuoteIds) aiMsg.sourceMessageIds = JSON.stringify(meta.sourceQuoteIds)
        },
        onError: (msg) => {
          ElMessage.error(msg || 'AI 出错了')
        },
        onDone: () => {
          streamingMsg.value = null
          streaming.value = false
        },
      },
      controller.signal,
    )
  } catch (e: any) {
    if (e?.name !== 'AbortError') {
      ElMessage.error(e?.message || '连接中断')
    }
  } finally {
    streaming.value = false
    streamingMsg.value = null
    abortRef.value = null
  }
}

function stop() {
  abortRef.value?.abort()
}

async function onClose() {
  if (!session.value) return
  try {
    await ElMessageBox.confirm(
      '关闭后，30 天内可恢复；之后将永久删除本次对话记录。',
      '关闭对话',
      { confirmButtonText: '关闭', cancelButtonText: '继续聊', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await closeHeartcoveSession(session.value.id)
    ElMessage.success('已关闭')
    router.push({ name: 'heartcove-entry' })
  } catch (e: any) {
    ElMessage.error(e?.message || '关闭失败')
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

onMounted(loadOrCreate)
onUnmounted(() => abortRef.value?.abort())
</script>

<template>
  <div class="hc-chat">
    <!-- 合规 banner：不可关闭 -->
    <div class="hc-chat__banner">
      <span class="hc-chat__banner-icon">⚠️</span>
      <span class="hc-chat__banner-text">
        此对话由 AI 基于既往采访素材生成，不代表先辈本人当下立场。如有紧急情况，请拨打心理援助热线 400-161-9995。
      </span>
    </div>

    <header class="hc-chat__header">
      <button class="hc-chat__back" @click="router.push({ name: 'heartcove-entry' })">
        ← 心声信箱
      </button>
      <h1 class="hc-chat__title" v-if="session">
        与「{{ session.subjectDisplayName }}」的对话
      </h1>
      <el-button text size="small" class="hc-chat__close" @click="onClose">关闭对话</el-button>
    </header>

    <div ref="scrollRef" class="hc-chat__scroll">
      <div v-if="!session" class="hc-chat__loading">
        <el-skeleton :rows="6" animated />
      </div>

      <template v-else>
        <div
          v-for="(m, idx) in session.messages"
          :key="idx"
          class="hc-chat__row"
          :class="{ 'hc-chat__row--user': m.role === 'user', 'hc-chat__row--ai': m.role === 'ai' }"
        >
          <div class="hc-chat__bubble">
            <div class="hc-chat__content">{{ m.content }}</div>
            <div class="hc-chat__foot" v-if="m.role === 'ai'">
              <span class="hc-chat__tag">AI 生成 · 基于采访素材</span>
              <span class="hc-chat__time">{{ formatDateTime(m.createdAt) }}</span>
            </div>
            <div class="hc-chat__foot" v-else>
              <span class="hc-chat__time">{{ formatDateTime(m.createdAt) }}</span>
            </div>
          </div>
        </div>
      </template>
    </div>

    <footer class="hc-chat__composer">
      <textarea
        v-model="inputText"
        :disabled="streaming"
        class="hc-chat__textarea"
        rows="2"
        placeholder="说点什么吧…（Enter 发送，Shift+Enter 换行）"
        @keydown="onKeydown"
      />
      <div class="hc-chat__composer-actions">
        <span v-if="streaming" class="hc-chat__hint">正在倾听…</span>
        <el-button v-if="streaming" size="small" @click="stop">停止</el-button>
        <el-button
          type="primary"
          size="small"
          :disabled="!inputText.trim() || streaming"
          @click="send"
        >发送</el-button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.hc-chat {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 60px - 32px);  /* 减去顶栏 + 留白 */
  max-width: 760px;
  margin: 0 auto;
  background: #f7f3ec;        /* 米白 */
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  overflow: hidden;
  position: relative;
}
.hc-chat__banner {
  flex-shrink: 0;
  background: rgba(217, 119, 6, 0.06);
  border-bottom: 1px solid var(--mw-border);
  padding: 10px 16px;
  font-size: 12px;
  color: var(--mw-text-secondary);
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 1.5;
}
.hc-chat__banner-icon {
  font-size: 14px;
  flex-shrink: 0;
}
.hc-chat__header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--mw-border);
  background: #fdf6ec;
}
.hc-chat__back {
  background: transparent;
  border: none;
  color: var(--mw-text-muted);
  font-size: 13px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}
.hc-chat__back:hover {
  background: rgba(0, 0, 0, 0.04);
}
.hc-chat__title {
  flex: 1;
  font-size: 16px;
  font-weight: 500;
  margin: 0;
  font-family: 'Songti SC', 'STSong', 'SimSun', serif;  /* 思源宋体回退 */
  color: #3a2e22;
}
.hc-chat__close {
  font-size: 12px;
  color: var(--mw-text-muted);
}
.hc-chat__scroll {
  flex: 1;
  overflow-y: auto;
  padding: 24px 16px;
  scroll-behavior: smooth;
}
.hc-chat__loading {
  padding: 40px 16px;
}
.hc-chat__row {
  display: flex;
  margin-bottom: 18px;
  animation: hc-fadein 0.6s ease;
}
@keyframes hc-fadein {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}
.hc-chat__row--user {
  justify-content: flex-end;
}
.hc-chat__row--ai {
  justify-content: flex-start;
}
.hc-chat__bubble {
  max-width: 76%;
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.8;
  font-size: 15px;
  font-family: 'Songti SC', 'STSong', 'SimSun', serif;
  word-break: break-word;
}
.hc-chat__row--user .hc-chat__bubble {
  background: #d97706;
  color: #fdf6ec;
  border-bottom-right-radius: 4px;
}
.hc-chat__row--ai .hc-chat__bubble {
  background: #fffdf7;
  color: #3a2e22;
  border: 1px solid #ece2cf;
  border-bottom-left-radius: 4px;
}
.hc-chat__content {
  white-space: pre-wrap;
}
.hc-chat__foot {
  margin-top: 8px;
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 8px;
  opacity: 0.7;
}
.hc-chat__row--user .hc-chat__foot {
  justify-content: flex-end;
  color: rgba(253, 246, 236, 0.7);
}
.hc-chat__row--ai .hc-chat__foot {
  color: var(--mw-text-muted);
}
.hc-chat__tag {
  background: rgba(217, 119, 6, 0.1);
  color: var(--mw-primary);
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 10px;
}
.hc-chat__time {
  font-size: 10px;
}
.hc-chat__composer {
  flex-shrink: 0;
  border-top: 1px solid var(--mw-border);
  background: #fdf6ec;
  padding: 12px 16px;
}
.hc-chat__textarea {
  width: 100%;
  resize: none;
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  padding: 10px 12px;
  font-size: 14px;
  font-family: inherit;
  background: #fff;
  color: var(--mw-text);
  outline: none;
  transition: border-color 0.2s;
}
.hc-chat__textarea:focus {
  border-color: var(--mw-primary);
}
.hc-chat__textarea:disabled {
  background: var(--mw-bg);
}
.hc-chat__composer-actions {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
.hc-chat__hint {
  font-size: 12px;
  color: var(--mw-text-muted);
  margin-right: auto;
  font-style: italic;
}
</style>