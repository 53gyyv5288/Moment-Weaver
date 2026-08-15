/**
 * 通知 API (M5-A.2)。
 * 后端路由：/api/v1/notifications/**
 */
import { backend } from './client'
import type { ApiResult, PageResult } from '@/types/api'

export type NotificationType =
  | 'DRAFT_PUBLISHED'
  | 'SHARE_CREATED'
  | 'SHARE_ACCESSED'
  | 'SHARE_REVOKED'
  | 'SHARE_EXPIRED'
  | 'EXPORT_READY'
  | 'DELETION_EXECUTED'
  | 'AUTHORIZATION_REVOKED'
  | 'AUTHORIZATION_REQUESTED'   // M11 Phase 2：被采访者收到授权邀请
  | 'AUTHORIZATION_GRANTED'     // M11 Phase 2：采访官收到"已同意"回执
  | 'UNKNOWN'
  | string

export interface NotificationVO {
  id: string
  type: NotificationType
  title: string
  body: string
  refId?: string
  deepLink?: string
  read: boolean
  readAt?: string
  createdAt: string
  metadata?: Record<string, unknown>
}

export interface UnreadCount {
  count: number
}

/** 通知列表（unreadOnly=true 只查未读） */
export function listNotifications(
  page = 1,
  size = 20,
  unreadOnly = false,
) {
  return backend.get<ApiResult<PageResult<NotificationVO>>>(
    `/v1/notifications?page=${page}&size=${size}&unreadOnly=${unreadOnly}`,
  )
}

/** 未读数（顶栏铃铛用） */
export function getUnreadCount() {
  return backend.get<ApiResult<UnreadCount>>('/v1/notifications/unread-count')
}

/** 标记单条已读 */
export function markRead(nid: string) {
  return backend.patch<ApiResult<void>>(`/v1/notifications/${nid}/read`)
}

/** 全部已读 */
export function markAllRead() {
  return backend.patch<ApiResult<{ updated: number }>>(
    '/v1/notifications/read-all',
  )
}

/** 通知类型 → 视觉映射（图标 / 颜色） */
export const NOTIFICATION_VISUAL: Record<string, { icon: string; type: 'info' | 'success' | 'warning' | 'danger' | '' }> = {
  DRAFT_PUBLISHED:       { icon: '📄', type: 'success' },
  SHARE_CREATED:         { icon: '🔗', type: 'info' },
  SHARE_ACCESSED:        { icon: '👁', type: 'info' },
  SHARE_REVOKED:         { icon: '🚫', type: 'warning' },
  SHARE_EXPIRED:         { icon: '⏰', type: 'warning' },
  EXPORT_READY:          { icon: '📦', type: 'success' },
  DELETION_EXECUTED:     { icon: '🗑', type: 'danger' },
  AUTHORIZATION_REVOKED: { icon: '⚠', type: 'warning' },
  AUTHORIZATION_REQUESTED: { icon: '📩', type: 'info' },
  AUTHORIZATION_GRANTED:   { icon: '✅', type: 'success' },
  UNKNOWN:               { icon: '🔔', type: '' },
}
