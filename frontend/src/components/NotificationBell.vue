<script setup lang="ts">
/**
 * 顶栏通知铃铛 (M5-A.2)。
 * - 红点 / 数字 badge 显示未读数
 * - hover 出 Popover，含最近 5 条
 * - 跳转 /notifications 查全部
 */
import { computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import { useNotificationStore } from '@/stores/notification'
import { NOTIFICATION_VISUAL, type NotificationVO } from '@/api/notification'

const store = useNotificationStore()
const router = useRouter()

const unread = computed(() => store.unreadCount)
const recent = computed<NotificationVO[]>(() => store.items.slice(0, 5))

function visual(type: string) {
  return NOTIFICATION_VISUAL[type] || NOTIFICATION_VISUAL.UNKNOWN
}

function formatTime(s?: string) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function onItemClick(n: NotificationVO, e: Event) {
  e.stopPropagation()
  if (!n.read) await store.markRead(n.id)
  if (n.deepLink) router.push(n.deepLink)
}

async function onRefresh() {
  await store.refreshList()
}

function onViewAll() {
  router.push('/notifications')
}

onMounted(async () => {
  await store.refreshList()  // 拉一次给 popover 用
  store.startPolling()
})
onUnmounted(() => {
  store.stopPolling()
})
</script>

<template>
  <el-popover
    placement="bottom-end"
    :width="360"
    trigger="hover"
    @show="onRefresh"
  >
    <template #reference>
      <div class="bell">
        <el-badge
          :value="unread"
          :hidden="unread === 0"
          :max="99"
          class="bell__badge"
        >
          <el-icon :size="20" class="bell__icon"><Bell /></el-icon>
        </el-badge>
      </div>
    </template>

    <div class="bell__panel">
      <div class="bell__head">
        <span>通知</span>
        <el-button v-if="unread > 0" link type="primary" @click="store.markAllRead()">全部已读</el-button>
      </div>

      <el-empty
        v-if="recent.length === 0"
        description="暂无通知"
        :image-size="60"
      />

      <ul v-else class="bell__list">
        <li
          v-for="n in recent"
          :key="n.id"
          class="bell__item"
          :class="{ 'bell__item--unread': !n.read }"
          @click="onItemClick(n, $event)"
        >
          <span class="bell__icon2" :class="`bell__icon2--${visual(n.type).type}`">
            {{ visual(n.type).icon }}
          </span>
          <div class="bell__body">
            <div class="bell__title">{{ n.title }}</div>
            <div class="bell__text">{{ n.body }}</div>
            <div class="bell__time">{{ formatTime(n.createdAt) }}</div>
          </div>
        </li>
      </ul>

      <div class="bell__foot">
        <el-button text type="primary" @click="onViewAll">查看全部 →</el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.bell {
  display: inline-flex; align-items: center; justify-content: center;
  width: 40px; height: 40px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}
.bell:hover { background: #f3f4f6; }
.bell__icon { color: #4b5563; }

.bell__panel { font-size: 13px; }
.bell__head {
  display: flex; justify-content: space-between; align-items: center;
  padding: 0 0 8px; border-bottom: 1px solid #e5e7eb;
  font-weight: 500; color: #1f2937;
}
.bell__list { list-style: none; padding: 0; margin: 0; max-height: 360px; overflow-y: auto; }
.bell__item {
  display: flex; gap: 10px; padding: 10px 4px;
  border-bottom: 1px solid #f3f4f6;
  cursor: pointer;
  transition: background 0.15s;
}
.bell__item:hover { background: #f9fafb; }
.bell__item--unread { background: #fffbeb; }
.bell__item--unread:hover { background: #fef3c7; }

.bell__icon2 {
  flex-shrink: 0; width: 32px; height: 32px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px;
  background: #f3f4f6;
}
.bell__icon2--info    { background: #dbeafe; }
.bell__icon2--success { background: #d1fae5; }
.bell__icon2--warning { background: #fef3c7; }
.bell__icon2--danger  { background: #fee2e2; }

.bell__body { flex: 1; min-width: 0; }
.bell__title { font-weight: 500; color: #1f2937; margin-bottom: 2px; }
.bell__text { color: #6b7280; font-size: 12px; line-height: 1.4;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.bell__time { color: #9ca3af; font-size: 11px; margin-top: 4px; }

.bell__foot {
  text-align: center; padding-top: 8px;
  border-top: 1px solid #e5e7eb;
}
</style>
