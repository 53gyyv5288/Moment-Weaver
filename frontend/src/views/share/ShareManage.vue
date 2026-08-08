<script setup lang="ts">
/**
 * 分享管理页（M5-A）。
 * 项目下所有分享链接的 CRUD 入口。
 * 路由：/projects/:id/shares
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Link, Delete, Document } from '@element-plus/icons-vue'
import { listShares, revokeShare, type ShareLinkVO } from '@/api/share'
import { listDrafts } from '@/api/draft'
import type { NarrativeDraftVO } from '@/types/api'
import { formatDateTime } from '@/utils/format'
import ShareCreateDialog from './ShareCreateDialog.vue'

const route = useRoute()
const projectId = computed(() => route.params.id as string)

const shares = ref<ShareLinkVO[]>([])
const drafts = ref<NarrativeDraftVO[]>([])
const loading = ref(false)
const showCreate = ref(false)
const defaultDraftId = ref<string | number | undefined>(undefined)

const STATUS_LABELS: Record<string, string> = {
  active: '有效',
  expired: '已过期',
  revoked: '已撤销',
}
const STATUS_TYPES: Record<string, '' | 'success' | 'info' | 'danger'> = {
  active: 'success',
  expired: 'info',
  revoked: 'danger',
}

async function load() {
  loading.value = true
  try {
    const { data } = await listShares(projectId.value)
    if (data?.code === 0) shares.value = data.data || []
    else ElMessage.error(data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadDrafts() {
  const { data } = await listDrafts(projectId.value, { size: 100 })
  if (data?.code === 0) {
    // 只允许已发布的成稿被分享
    drafts.value = (data.data || []).filter(d => d.status === 'published')
  }
}

async function onCreate(draftId?: string | number) {
  defaultDraftId.value = draftId
  showCreate.value = true
}

async function onRevoke(s: ShareLinkVO) {
  try {
    await ElMessageBox.confirm(
      `确定要撤销分享「${s.draftTitle || s.id}」吗？撤销后该链接立即失效。`,
      '撤销分享',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const { data } = await revokeShare(s.id)
  if (data?.code === 0) {
    ElMessage.success('已撤销')
    await load()
  } else {
    ElMessage.error(data?.message || '撤销失败')
  }
}

function onCopyUrl(s: ShareLinkVO) {
  if (!s.shareUrl) return
  navigator.clipboard.writeText(s.shareUrl)
    .then(() => ElMessage.success('已复制分享链接'))
    .catch(() => ElMessage.warning('复制失败，请手动复制'))
}

function onOpenShare(s: ShareLinkVO) {
  window.open(s.shareUrl, '_blank', 'noopener')
}

function formatTime(s?: string) {
  return formatDateTime(s) || '—'
}

onMounted(() => { loadDrafts(); load() })
</script>

<template>
  <div class="sm" v-loading="loading">
    <div class="sm__bar">
      <span class="muted">将已发布的成稿通过公开链接或密码链接分享给他人阅读</span>
      <el-button type="primary" :icon="Link" @click="onCreate()">新建分享</el-button>
    </div>

    <el-empty
      v-if="!loading && shares.length === 0"
      :description="drafts.length === 0
        ? '该项目暂无已发布的成稿，请先到「成稿」页发布后再分享'
        : '还没有分享链接，点击「新建分享」开始'"
    />

    <div v-else class="sm__list">
      <article v-for="s in shares" :key="s.id" class="sm__card">
        <div class="sm__cardHead">
          <div class="sm__cardTitle">
            <span class="sm__emoji">📄</span>
            <span class="sm__titleText">{{ s.draftTitle || `成稿 #${s.draftId}` }}</span>
          </div>
          <el-tag size="small" :type="STATUS_TYPES[s.status]">{{ STATUS_LABELS[s.status] || s.status }}</el-tag>
          <el-tag v-if="s.hasPassword" size="small" effect="plain">🔒 密码</el-tag>
        </div>

        <div class="sm__cardBody">
          <div class="sm__urlRow">
            <code class="sm__url">{{ s.shareUrl }}</code>
            <el-button size="small" :icon="Document" @click="onCopyUrl(s)">复制</el-button>
            <el-button
              v-if="s.status === 'active'"
              size="small"
              type="primary"
              plain
              @click="onOpenShare(s)"
            >打开</el-button>
          </div>
          <div class="sm__meta">
            <span>👁 {{ s.viewCount }} 次访问</span>
            <span>由 {{ s.createdByName || '—' }} 创建</span>
            <span>创建于 {{ formatTime(s.createdAt) }}</span>
            <span>有效期至 {{ formatTime(s.expiresAt) }}</span>
            <span v-if="s.lastAccessedAt">最近访问 {{ formatTime(s.lastAccessedAt) }}</span>
          </div>
          <div class="sm__perms">
            <el-tag v-if="s.allowCopy" size="small" effect="plain">允许复制</el-tag>
            <el-tag v-else size="small" effect="plain" type="info">禁止复制</el-tag>
            <el-tag v-if="s.allowDownload" size="small" effect="plain">允许下载</el-tag>
            <el-tag v-else size="small" effect="plain" type="info">禁止下载</el-tag>
          </div>
        </div>

        <div class="sm__cardFoot">
          <el-button
            v-if="s.status === 'active'"
            size="small"
            type="danger"
            plain
            :icon="Delete"
            @click="onRevoke(s)"
          >撤销</el-button>
          <span v-else class="sm__dead">该链接已不可用</span>
        </div>
      </article>
    </div>

    <ShareCreateDialog
      v-model="showCreate"
      :project-id="projectId"
      :drafts="drafts"
      :default-draft-id="defaultDraftId"
      @created="load"
    />
  </div>
</template>

<style scoped>
.sm { width: 100%; max-width: 960px; }
.sm__bar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 12px; background: var(--mw-surface); border-radius: var(--mw-radius);
  border: 1px solid var(--mw-border); margin-bottom: 16px;
}
.muted { color: var(--mw-text-secondary); font-size: 13px; }

.sm__list { display: flex; flex-direction: column; gap: 12px; }
.sm__card {
  background: var(--mw-surface); border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  padding: 16px; display: flex; flex-direction: column; gap: 12px;
}
.sm__cardHead { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sm__cardTitle { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0; }
.sm__emoji { font-size: 18px; }
.sm__titleText {
  font-size: 15px; font-weight: 500; color: var(--mw-text);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.sm__cardBody { display: flex; flex-direction: column; gap: 8px; }
.sm__urlRow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sm__url {
  flex: 1; min-width: 200px; padding: 6px 10px;
  background: var(--mw-cream); border-radius: 4px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px; color: var(--mw-text-secondary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.sm__meta {
  display: flex; gap: 12px; flex-wrap: wrap;
  font-size: 12px; color: var(--mw-text-muted);
}
.sm__perms { display: flex; gap: 6px; }
.sm__cardFoot {
  display: flex; justify-content: flex-end;
  padding-top: 8px; border-top: 1px dashed var(--mw-border);
}
.sm__dead { color: var(--mw-text-muted); font-size: 12px; }
</style>
