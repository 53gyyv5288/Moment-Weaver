/**
 * 通知 Pinia Store (M5-A.2)。
 *
 * 职责：
 * - 缓存未读数（顶栏铃铛用）
 * - 30s 轮询拉新未读数
 * - 缓存通知列表（NotificationList 视图用）
 *
 * 设计：单实例轮询，避免每页重复打 /unread-count。
 */
import { defineStore } from 'pinia'
import {
  getUnreadCount,
  listNotifications,
  markRead,
  markAllRead,
  type NotificationVO,
} from '@/api/notification'

const POLL_INTERVAL_MS = 30_000

export const useNotificationStore = defineStore('notification', {
  state: () => ({
    unreadCount: 0,
    items: [] as NotificationVO[],
    loading: false,
    loadingList: false,
    total: 0,
    page: 0,
    size: 20,
    unreadOnly: false,
    pollTimer: null as ReturnType<typeof setInterval> | null,
  }),

  actions: {
    /** 拉一次未读数。组件 mount / 路由切换 / 手动刷新都用这个 */
    async refreshUnread() {
      try {
        const { data } = await getUnreadCount()
        if (data?.code === 0) this.unreadCount = data.data?.count ?? 0
      } catch (e) {
        // 静默失败，不打扰
      }
    },

    /** 拉一次列表（按当前 page/size/unreadOnly） */
    async refreshList() {
      this.loadingList = true
      try {
        const { data } = await listNotifications(this.page, this.size, this.unreadOnly)
        if (data?.code === 0) {
          this.items = data.data?.records || []
          this.total = data.data?.total || 0
        }
      } finally {
        this.loadingList = false
      }
    },

    async setUnreadOnly(v: boolean) {
      this.unreadOnly = v
      this.page = 0
      await this.refreshList()
    },

    async nextPage() {
      if ((this.page + 1) * this.size >= this.total) return
      this.page += 1
      await this.refreshList()
    },

    async prevPage() {
      if (this.page === 0) return
      this.page -= 1
      await this.refreshList()
    },

    async markRead(nid: string) {
      await markRead(nid)
      const idx = this.items.findIndex(i => i.id === nid)
      if (idx >= 0 && this.items[idx].read === false) {
        this.items[idx].read = true
        if (this.unreadCount > 0) this.unreadCount -= 1
      }
    },

    async markAllRead() {
      const { data } = await markAllRead()
      if (data?.code === 0) {
        this.items = this.items.map(i => ({ ...i, read: true }))
        this.unreadCount = 0
      }
    },

    /** 启动 30s 轮询（仅在登录后调用一次） */
    startPolling() {
      this.stopPolling()
      // 立即拉一次
      this.refreshUnread()
      this.pollTimer = setInterval(() => {
        this.refreshUnread()
      }, POLL_INTERVAL_MS)
    },

    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer)
        this.pollTimer = null
      }
    },
  },
})
