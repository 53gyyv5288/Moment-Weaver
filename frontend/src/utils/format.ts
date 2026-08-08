/**
 * 时间格式化工具。
 * 后端返回的是 ISO 字符串（如 2026-08-08T15:19:30），直接展示可读性差。
 */

/** 2026-08-08T15:19:30 → 2026-08-08 15:19 */
export function formatDateTime(s?: string | null): string {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return `${fmtDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 2026-08-08T15:19:30 → 2026-08-08 */
export function formatDate(s?: string | null): string {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  return fmtDate(d)
}

function fmtDate(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}
