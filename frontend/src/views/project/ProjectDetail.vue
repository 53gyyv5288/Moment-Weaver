<script setup lang="ts">
/**
 * 项目详情：M2 阶段承载 Subject 列表 + 授权管理 + 启动采访。
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Plus, ChatLineRound, Delete, Link, Edit, ArrowDown } from '@element-plus/icons-vue'
import {
  listSubjects,
  createSubject,
  updateSubject,
  deleteSubject,
  type SubjectVO,
} from '@/api/subject'
import {
  listAuthorizationsByProject,
  createAuthorization,
  revokeAuthorization,
  type AuthorizationVO,
} from '@/api/authorization'
import { startInterview } from '@/api/interview'
import AssetUploader from '@/views/asset/AssetUploader.vue'
import AssetList from '@/views/asset/AssetList.vue'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id as string)

// 项目名称 / 类型 / 描述由 ProjectLayout 页头承担，本页只管人物 + 授权 + 素材
const subjects = ref<SubjectVO[]>([])
const authorizations = ref<AuthorizationVO[]>([])
const loading = ref(false)
const newSubject = ref({ displayName: '', relation: '', note: '' })
const showAddSubject = ref(false)
// 编辑人物
const editForm = ref({ displayName: '', relation: '', note: '' })
const editingSubject = ref<SubjectVO | null>(null)
const showEditSubject = ref(false)
const savingEdit = ref(false)
const newAuthz = ref({ subjectId: '', scopes: ['interview'] as string[] })
const showCreateAuthz = ref(false)

const SCOPE_LABELS: Record<string, string> = {
  interview: 'AI 采访对话',
  narrative: 'AI 撰写成稿',
  asset: '使用上传素材',
  share: '同意被分享',
}

async function load() {
  loading.value = true
  try {
    const [subj, authz] = await Promise.all([
      listSubjects(projectId.value),
      listAuthorizationsByProject(projectId.value),
    ])
    if (subj.data && subj.data.code === 0) subjects.value = subj.data.data || []
    if (authz.data && authz.data.code === 0) authorizations.value = authz.data.data || []
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function onAddSubject() {
  if (!newSubject.value.displayName.trim()) {
    ElMessage.warning('请输入姓名')
    return
  }
  const { data } = await createSubject(projectId.value, {
    displayName: newSubject.value.displayName.trim(),
    relation: newSubject.value.relation.trim() || undefined,
    note: newSubject.value.note.trim() || undefined,
  })
  if (data && data.code === 0) {
    ElMessage.success('已添加')
    showAddSubject.value = false
    newSubject.value = { displayName: '', relation: '', note: '' }
    await load()
  } else {
    ElMessage.error(data?.message || '添加失败')
  }
}

async function onDeleteSubject(s: SubjectVO) {
  await ElMessageBox.confirm(
    `确定删除人物「${s.displayName}」？相关授权与采访记录不会被物理删除。`,
    '确认',
    { type: 'warning' },
  ).catch(() => null)
  const { data } = await deleteSubject(projectId.value, s.id)
  if (data && data.code === 0) {
    ElMessage.success('已删除')
    await load()
  }
}

function onOpenEditSubject(s: SubjectVO) {
  editingSubject.value = s
  editForm.value = {
    displayName: s.displayName,
    relation: s.relation ?? '',
    note: s.note ?? '',
  }
  showEditSubject.value = true
}

async function onSaveEditSubject() {
  if (!editingSubject.value) return
  // 后端 @AssertTrue 要求至少一个字段；前端兜底
  if (
    !editForm.value.displayName.trim() &&
    !editForm.value.relation.trim() &&
    !editForm.value.note.trim()
  ) {
    ElMessage.warning('请至少修改一个字段')
    return
  }
  savingEdit.value = true
  try {
    const { data } = await updateSubject(projectId.value, editingSubject.value.id, {
      displayName: editForm.value.displayName.trim() || undefined,
      relation: editForm.value.relation.trim() || undefined,
      note: editForm.value.note.trim() || undefined,
    })
    if (data && data.code === 0) {
      ElMessage.success('已保存')
      showEditSubject.value = false
      editingSubject.value = null
      await load()
    } else {
      ElMessage.error(data?.message || '保存失败')
    }
  } finally {
    savingEdit.value = false
  }
}

async function onCreateAuthz() {
  if (!newAuthz.value.subjectId) {
    ElMessage.warning('请选择人物')
    return
  }
  const { data } = await createAuthorization(projectId.value, {
    subjectId: newAuthz.value.subjectId,
    scopes: newAuthz.value.scopes,
  })
  if (data && data.code === 0) {
    ElMessage.success('授权链接已生成')
    showCreateAuthz.value = false
    await navigator.clipboard.writeText(data.data!.publicUrl || '')
    ElMessage.info('链接已复制到剪贴板')
    await load()
  } else {
    ElMessage.error(data?.message || '生成失败')
  }
}

async function onRevokeAuthz(a: AuthorizationVO) {
  await ElMessageBox.confirm(
    `撤销该授权？撤销后授权链接立即失效。`,
    '确认',
    { type: 'warning' },
  ).catch(() => null)
  const { data } = await revokeAuthorization(a.id)
  if (data && data.code === 0) {
    ElMessage.success('已撤销')
    await load()
  }
}

function copyUrl(a: AuthorizationVO) {
  if (!a.publicUrl) return
  navigator.clipboard.writeText(a.publicUrl)
  ElMessage.success('已复制授权链接')
}

function statusType(s?: string) {
  if (!s) return 'info'
  if (s === 'granted') return 'success'
  if (s === 'denied') return 'danger'
  if (s === 'revoked') return 'warning'
  if (s === 'expired') return 'info'
  return 'info'
}
function statusLabel(s?: string) {
  return { pending: '待同意', granted: '已同意', denied: '已拒绝', revoked: '已撤销', expired: '已过期' }[s || ''] || s
}

async function onStartInterview(s: SubjectVO) {
  if (s.latestAuthStatus !== 'granted') {
    ElMessage.warning('该人物尚未同意授权，无法开始采访')
    return
  }
  const { data } = await startInterview({
    projectId: projectId.value,
    subjectId: s.id,
    authorizationId: s.latestAuthId,
  })
  if (data && data.code === 0 && data.data) {
    router.push(`/interview/${data.data.id}`)
  } else {
    ElMessage.error(data?.message || '启动失败')
  }
}

const assetListRef = ref<InstanceType<typeof AssetList> | null>(null)
function loadAssets() {
  assetListRef.value?.load?.()
}
</script>

<template>
  <div class="pd" v-loading="loading">
    <el-tabs>
      <!-- ============== 人物 ============== -->
      <el-tab-pane label="被采访者">
        <template #label>
          <span>被采访者 ({{ subjects.length }})</span>
        </template>
        <div class="pd__actions">
          <el-button type="primary" :icon="Plus" @click="showAddSubject = true">添加人物</el-button>
        </div>

        <el-empty v-if="subjects.length === 0" description="还没有人物，先添加一个吧" />

        <el-table v-else :data="subjects" stripe>
          <el-table-column prop="displayName" label="姓名" width="160" />
          <el-table-column label="关系" width="120">
            <template #default="{ row }">
              <span v-if="row.relation">{{ row.relation }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="最新授权" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.latestAuthStatus" :type="statusType(row.latestAuthStatus)" size="small">
                {{ statusLabel(row.latestAuthStatus) }}
              </el-tag>
              <span v-else class="muted">未发起</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.note">{{ row.note }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="240" align="right" fixed="right">
            <template #default="{ row }">
              <el-button size="small" :icon="Link" @click="newAuthz.subjectId = row.id; showCreateAuthz = true">
                发起授权
              </el-button>
              <el-button
                size="small"
                type="primary"
                :icon="ChatLineRound"
                :disabled="row.latestAuthStatus !== 'granted'"
                @click="onStartInterview(row)"
              >
                采访
              </el-button>
              <el-dropdown trigger="click" @command="(c: string) => c === 'edit' ? onOpenEditSubject(row) : onDeleteSubject(row)">
                <el-button size="small" :icon="ArrowDown" />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit" :icon="Edit">编辑</el-dropdown-item>
                    <el-dropdown-item command="delete" :icon="Delete" divided>删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============== 授权 ============== -->
      <el-tab-pane label="授权记录">
        <template #label>
          <span>授权记录 ({{ authorizations.length }})</span>
        </template>
        <el-empty v-if="authorizations.length === 0" description="尚未发起任何授权" />
        <el-table v-else :data="authorizations" stripe>
          <el-table-column label="人物" width="160">
            <template #default="{ row }">
              {{ subjects.find(s => String(s.id) === String(row.subjectId))?.displayName || row.subjectId }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="范围">
            <template #default="{ row }">
              <el-tag v-for="sc in row.scopes" :key="sc" size="small" effect="plain" style="margin-right: 4px">
                {{ SCOPE_LABELS[sc] || sc }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="过期" width="180">
            <template #default="{ row }">
              <span v-if="row.expiresAt">{{ formatDateTime(row.expiresAt) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.publicUrl" size="small" :icon="CopyDocument" @click="copyUrl(row)">复制链接</el-button>
              <el-button
                v-if="['pending', 'granted'].includes(row.status)"
                size="small"
                type="danger"
                @click="onRevokeAuthz(row)"
              >
                撤销
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============== 素材 ============== -->
      <el-tab-pane label="素材">
        <template #label>
          <span>素材</span>
        </template>
        <AssetUploader :project-id="projectId" @uploaded="loadAssets" />
        <el-divider />
        <AssetList ref="assetListRef" :project-id="projectId" @changed="loadAssets" />
      </el-tab-pane>
    </el-tabs>

    <!-- 添加人物对话框 -->
    <el-dialog v-model="showAddSubject" title="添加被采访者" width="500px">
      <el-form label-width="80px">
        <el-form-item label="姓名" required>
          <el-input v-model="newSubject.displayName" placeholder="如：父亲 / 王淑芬" />
        </el-form-item>
        <el-form-item label="关系">
          <el-input v-model="newSubject.relation" placeholder="如：父亲、外婆、本人" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="newSubject.note" type="textarea" :rows="2" placeholder="选填，自己看的小抄" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddSubject = false">取消</el-button>
        <el-button type="primary" @click="onAddSubject">添加</el-button>
      </template>
    </el-dialog>

    <!-- 编辑人物对话框 -->
    <el-dialog v-model="showEditSubject" title="编辑被采访者" width="500px">
      <el-form label-width="80px" v-if="editingSubject">
        <el-form-item label="姓名">
          <el-input v-model="editForm.displayName" placeholder="如：父亲 / 王淑芬" />
        </el-form-item>
        <el-form-item label="关系">
          <el-input v-model="editForm.relation" placeholder="如：父亲、外婆、本人" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.note" type="textarea" :rows="3" placeholder="选填，自己看的小抄" />
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon>
          <template #title>只提交修改的字段</template>
          至少修改一项（姓名/关系/备注）才能保存。
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="showEditSubject = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" @click="onSaveEditSubject">保存</el-button>
      </template>
    </el-dialog>

    <!-- 发起授权对话框 -->
    <el-dialog v-model="showCreateAuthz" title="发起授权" width="500px">
      <el-form label-width="100px">
        <el-form-item label="人物">
          <el-select v-model="newAuthz.subjectId" placeholder="选择人物" style="width: 100%">
            <el-option
              v-for="s in subjects"
              :key="s.id"
              :label="s.displayName + (s.relation ? '（' + s.relation + '）' : '')"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="授权范围">
          <el-checkbox-group v-model="newAuthz.scopes">
            <el-checkbox value="interview">AI 采访对话</el-checkbox>
            <el-checkbox value="narrative">AI 撰写成稿</el-checkbox>
            <el-checkbox value="asset">使用上传素材</el-checkbox>
            <el-checkbox value="share">同意被分享</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon>
          <template #title>生成后将自动复制公开链接到剪贴板</template>
          您可以手动把链接发给被采访者，对方打开后能看到同意书并选择同意/拒绝。
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="showCreateAuthz = false">取消</el-button>
        <el-button type="primary" @click="onCreateAuthz">生成链接</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pd { width: 100%; }
.pd__actions { margin-bottom: 12px; }
.muted { color: var(--mw-text-muted); }
</style>
