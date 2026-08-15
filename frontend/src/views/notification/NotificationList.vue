<script setup lang="ts">
/**
 * 通知中心列表 (M5-A.2)。
 * 路由：/notifications
 * - 顶部 tab: 全部 / 未读
 * - 列表项: 类型图标 + 标题 + 正文 + 时间 + 跳转
 * - 未读项: 高亮
 * - 底部: 翻页 + 全部已读
 */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useNotificationStore } from '@/stores/notification'
import { NOTIFICATION_VISUAL, type NotificationVO } from '@/api/notification'

const store = useNotificationStore()
const router = useRouter()

const items = computed<NotificationVO[]>(() => store.items)
const total = computed(() => store.total)
const page = computed(() => store.page)
const size = computed(() => store.size)
// 1-based 分页（与后端 PageResult 对齐）
const hasNext = computed(() => page.value * size.value < total.value)
const hasPrev = computed(() => page.value > 1)

function visual(type: string) {
  return NOTIFICATION_VISUAL[type] || NOTIFICATION_VISUAL.UNKNOWN
}

function formatTime(s?: string) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function onItemClick(n: NotificationVO) {
  if (!n.read) await store.markRead(n.id)
  if (n.deepLink) router.push(n.deepLink)
}

async function onMarkAllRead() {
  await store.markAllRead()
  ElMessage.success('已全部标记为已读')
}

onMounted(() => store.refreshList())
</script>

<template>
  <div class="nl">
    <header class="nl__head">
      <div class="nl__title">
        <h2>通知中心</h2>
        <p class="muted">项目动态、分享访问、导出/删除结果等都会汇集在这里</p>
      </div>
      <el-button :disabled="store.unreadCount === 0" @click="onMarkAllRead">全部标记为已读</el-button>
    </header>

    <el-tabs v-model="store.unreadOnly" @tab-change="(v: any) => store.setUnreadOnly(v === 'unread')">
      <el-tab-pane label="全部" :name="false" />
      <el-tab-pane :label="`未读 (${store.unreadCount})`" name="unread" />
    </el-tabs>

    <el-empty
      v-if="!store.loadingList && items.length === 0"
      :description="store.unreadOnly ? '没有未读通知' : '暂无通知'"
    />

    <ul v-else class="nl__list" v-loading="store.loadingList">
      <li
        v-for="n in items"
        :key="n.id"
        class="nl__item"
        :class="{ 'nl__item--unread': !n.read }"
        @click="onItemClick(n)"
      >
        <span class="nl__icon" :class="`nl__icon--${visual(n.type).type}`">
          {{ visual(n.type).icon }}
        </span>
        <div class="nl__body">
          <div class="nl__row1">
            <span class="nl__titleText">{{ n.title || '通知' }}</span>
            <el-tag
              v-if="!n.read"
              size="small"
              type="danger"
              effect="plain"
              class="nl__unread"
            >未读</el-tag>
            <span class="nl__time">{{ formatTime(n.createdAt) }}</span>
          </div>
          <div class="nl__text">{{ n.body }}</div>
        </div>
      </li>
    </ul>

    <div v-if="items.length > 0" class="nl__pager">
      <el-button :disabled="!hasPrev" @click="store.prevPage">上一页</el-button>
      <span class="nl__pagerInfo">第 {{ page + 1 }} 页 / 共 {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
      <el-button :disabled="!hasNext" @click="store.nextPage">下一页</el-button>
    </div>
  </div>
</template>

<style scoped>
.nl { max-width: 760px; margin: 0 auto; }
.nl__head {
  display: flex; align-items: center; gap: 16px;
  padding-bottom: 12px; border-bottom: 1px solid #e5e7eb; margin-bottom: 16px;
}
.nl__title { flex: 1; }
.nl__title h2 { margin: 0; }
.muted { color: #6b7280; font-size: 13px; margin: 4px 0 0; }

.nl__list { list-style: none; padding: 0; margin: 0; }
.nl__item {
  display: flex; gap: 12px; padding: 14px 16px;
  background: #fff; border: 1px solid #e5e7eb; border-radius: 8px;
  margin-bottom: 8px; cursor: pointer;
  transition: all 0.15s;
}
.nl__item:hover { border-color: #2563eb; box-shadow: 0 2px 8px rgba(37, 99, 235, 0.06); }
.nl__item--unread {
  background: #fffbeb;
  border-color: #fde68a;
}

.nl__icon {
  flex-shrink: 0;
  width: 36px; height: 36px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
  background: #f3f4f6;
}
.nl__icon--info    { background: #dbeafe; }
.nl__icon--success { background: #d1fae5; }
.nl__icon--warning { background: #fef3c7; }
.nl__icon--danger  { background: #fee2e2; }

.nl__body { flex: 1; min-width: 0; }
.nl__row1 {
  display: flex; align-items: center; gap: 8px;
  margin-bottom: 4px;
}
.nl__titleText { font-weight: 500; color: #1f2937; flex: 1; }
.nl__unread { margin-left: 0; }
.nl__time { color: #9ca3af; font-size: 12px; }
.nl__text { color: #4b5563; font-size: 13px; line-height: 1.5; }

.nl__pager {
  display: flex; align-items: center; justify-content: center; gap: 12px;
  margin-top: 16px;
}
.nl__pagerInfo { color: #6b7280; font-size: 13px; }
</style>
