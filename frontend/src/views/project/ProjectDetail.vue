<script setup lang="ts">
/**
 * 项目详情：M2 阶段承载 Subject 列表 + 授权管理 + 启动采访。
 * M11 Phase 2：人物可从家族成员里选，或纯匿名（兼容老流程）。
 * M11 Phase 3：按角色（admin/editor/viewer）控制按钮显隐。
 */
import { inject, onMounted, ref, computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Plus, ChatLineRound, Delete, Link, Edit, ArrowDown, User, UserFilled } from '@element-plus/icons-vue'
import {
  listSubjects,
  listEligibleSubjects,
  createSubject,
  updateSubject,
  deleteSubject,
  type SubjectVO,
  type EligibleFamilyMemberVO,
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
import { useProjectPermission } from '@/composables/useProjectPermission'
import { useAuthStore } from '@/stores/auth'
import type { ProjectVO } from '@/api/project'
import { getHeartcoveStatus, type HeartcoveStatus } from '@/api/heartcove'
import HeartcoveEnableDialog from '@/views/heartcove/HeartcoveEnableDialog.vue'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => route.params.id as string)

// 项目名称 / 类型 / 描述由 ProjectLayout 页头承担，本页只管人物 + 授权 + 素材
const subjects = ref<SubjectVO[]>([])

// M11 Phase 3：从 ProjectLayout 注入 project，按角色控制按钮
const project = inject<import('vue').Ref<ProjectVO | null>>('project')!
const { canEdit, canManage, isReadonly } = useProjectPermission(project)

// M11 Phase 3：判断"我是不是被采访者本人"——只有本人能看到"开始采访"按钮
const auth = useAuthStore()
const currentUserId = computed(() => auth.user?.id ? String(auth.user.id) : null)
// 被采访者本人判断：subject.linkedUserId == currentUserId
const isSubjectSelf = (s: SubjectVO) =>
  !!s.linkedUserId && !!currentUserId.value && String(s.linkedUserId) === currentUserId.value
// 匿名 subject（没 linkedUserId）—— 没有"被采访者本人"概念，由 userA 代答
const isAnonymousSubject = (s: SubjectVO) => !s.linkedUserId
// M12+：个人项目（familyId == null）—— UI 极简，只有"开始采访"按钮
//  个人项目创建时已自动生成 "我本人" subject + 自授权 granted（后端事件 bootstrap）
const isPersonal = computed(() => !project.value?.familyId)
// 个人项目下，找到那个"我本人"subject（linkedUserId == currentUserId）
const meSubject = computed(() =>
  isPersonal.value
    ? subjects.value.find(s => isSubjectSelf(s)) ?? null
    : null
)
const authorizations = ref<AuthorizationVO[]>([])
const loading = ref(false)
/** M12+：个人项目"开始采访"按钮 loading 态 */
const starting = ref(false)

// M13+ 心声信箱：每个 subject 的状态缓存
const heartcoveMap = ref<Record<number, HeartcoveStatus | null>>({})
const heartcoveEntries = computed(() =>
  Object.values(heartcoveMap.value).filter((s): s is HeartcoveStatus => !!s && s.enabled === 1),
)
const showHeartcoveEnable = ref(false)
const heartcoveEnableTarget = ref<{ id: number; name: string } | null>(null)

async function loadHeartcove() {
  const next: Record<number, HeartcoveStatus | null> = {}
  for (const s of subjects.value) {
    try {
      next[s.id] = await getHeartcoveStatus(s.id)
    } catch {
      next[s.id] = null
    }
  }
  heartcoveMap.value = next
}

function openHeartcoveEnable(id: number, name: string) {
  heartcoveEnableTarget.value = { id, name }
  showHeartcoveEnable.value = true
}

function enterHeartcove(subjectId: number) {
  router.push({ name: 'heartcove-chat', params: { subjectId } })
}

async function onHeartcoveEnabled() {
  showHeartcoveEnable.value = false
  await loadHeartcove()
}

// 在 load() 末尾拉心信箱状态（需要找到原 load 函数的结束位置）
const showAddSubject = ref(false)
const addSubjectTab = ref<'family' | 'anonymous'>('family')  // 默认显示「从家族成员选」
const eligibleMembers = ref<EligibleFamilyMemberVO[]>([])
const loadingEligible = ref(false)
const selectedFmId = ref<string | null>(null)  // 选中的 family_member.id
const anonymousForm = reactive({ displayName: '', relation: '', note: '' })
// 路径 1 选了家族成员后，留一个 relation 字段（家族成员关系由用户在 relation 里补充）
const familyMemberRelation = ref('')

async function loadEligible() {
  if (!showAddSubject.value) return
  loadingEligible.value = true
  try {
    const { data } = await listEligibleSubjects(projectId.value)
    if (data?.code === 0) eligibleMembers.value = data.data || []
    else eligibleMembers.value = []
  } finally {
    loadingEligible.value = false
  }
}

function openAddSubject() {
  showAddSubject.value = true
  addSubjectTab.value = eligibleMembers.value.length > 0 ? 'family' : 'anonymous'
  selectedFmId.value = null
  familyMemberRelation.value = ''
  anonymousForm.displayName = ''
  anonymousForm.relation = ''
  anonymousForm.note = ''
  loadEligible()
}

async function onAddSubject() {
  if (addSubjectTab.value === 'family') {
    if (!selectedFmId.value) {
      ElMessage.warning('请选择一位家族成员')
      return
    }
    const fm = eligibleMembers.value.find((m) => m.familyMemberId === selectedFmId.value)
    if (fm?.hasSubject) {
      ElMessage.warning('该成员已被添加为被采访者')
      return
    }
    var payload: any = {
      familyMemberId: selectedFmId.value,
      relation: familyMemberRelation.value.trim() || undefined,
    }
  } else {
    if (!anonymousForm.displayName.trim()) {
      ElMessage.warning('请输入姓名')
      return
    }
    var payload: any = {
      displayName: anonymousForm.displayName.trim(),
      relation: anonymousForm.relation.trim() || undefined,
      note: anonymousForm.note.trim() || undefined,
    }
  }
  const { data } = await createSubject(projectId.value, payload)
  if (data && data.code === 0) {
    ElMessage.success('已添加')
    showAddSubject.value = false
    await load()
  } else {
    ElMessage.error(data?.message || '添加失败')
  }
}

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
  // M13+ 心声信箱：拉取每个 subject 的状态
  loadHeartcove()
}

onMounted(load)

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
  // 找到被采访者，判断是否是家族成员（有 linkedUserId 就有账号）
  const subj = subjects.value.find(s => String(s.id) === String(newAuthz.value.subjectId))
  const isFamilyMember = !!(subj?.linkedUserId)
  const { data } = await createAuthorization(projectId.value, {
    subjectId: newAuthz.value.subjectId,
    scopes: newAuthz.value.scopes,
  })
  if (data && data.code === 0) {
    showCreateAuthz.value = false
    if (isFamilyMember) {
      // M11 Phase 2：家族成员会收到站内通知，无需复制链接
      ElMessage.success('授权邀请已发送，被采访者将在通知中心看到')
    } else {
      // 匿名被采访者：还是需要复制链接给 TA
      ElMessage.success('授权链接已生成（被采访者无账号，请手动发给 TA）')
      try {
        await navigator.clipboard.writeText(data.data!.publicUrl || '')
        ElMessage.info('链接已复制到剪贴板')
      } catch {
        ElMessage.warning('复制失败，请手动复制：' + data.data!.publicUrl)
      }
    }
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
  starting.value = true
  try {
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
  } finally {
    starting.value = false
  }
}

const assetListRef = ref<InstanceType<typeof AssetList> | null>(null)
function loadAssets() {
  assetListRef.value?.load?.()
}
</script>

<template>
  <div class="pd" v-loading="loading">
    <!-- ============== 个人项目：极简 UI ============== -->
    <div v-if="isPersonal" class="pd__personal">
      <el-card shadow="never" class="pd__personal-card">
        <h2 class="pd__personal-title">开始你的个人采访</h2>
        <p class="pd__personal-subtitle">
          AI 采访官会以温暖、有耐心、尊重长辈的态度，引导你回忆你的人生。
        </p>
        <el-button
          v-if="meSubject"
          type="primary"
          size="large"
          :icon="ChatLineRound"
          :loading="starting"
          @click="onStartInterview(meSubject)"
        >
          开始采访
        </el-button>
        <div v-else class="muted pd__personal-loading">
          正在为你准备采访空间…
        </div>
        <p v-if="meSubject" class="pd__personal-hint muted">
          被采访者：<strong>{{ meSubject.displayName }}</strong>（{{ meSubject.relation }}）
        </p>
      </el-card>
    </div>

    <!-- ============== 家族项目：原 tabs UI ============== -->
    <el-tabs v-else>
      <!-- ============== 人物 ============== -->
      <el-tab-pane label="被采访者">
        <template #label>
          <span>被采访者 ({{ subjects.length }})</span>
        </template>
        <div class="pd__actions">
          <!-- M11 Phase 3：添加人物按钮仅 admin/editor 可见 -->
          <el-button v-if="canEdit" type="primary" :icon="Plus" @click="openAddSubject">添加人物</el-button>
          <el-tag v-else-if="isReadonly" type="info" size="small">只读</el-tag>
        </div>

        <el-empty v-if="subjects.length === 0" description="还没有人物，先添加一个吧" />

        <el-table v-else :data="subjects" stripe>
          <el-table-column label="姓名" width="200">
            <template #default="{ row }">
              <div class="pd__subjName">
                <el-icon><User /></el-icon>
                <span>{{ row.displayName }}</span>
                <el-tag v-if="row.familyMemberId" type="warning" size="small" effect="plain" style="margin-left: 4px">
                  🏠 家人
                </el-tag>
              </div>
            </template>
          </el-table-column>
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
              <!-- M11 Phase 3：发起授权 admin + editor 都可见（后端 requireEditor 校验） -->
              <el-button v-if="canEdit" size="small" :icon="Link" @click="newAuthz.subjectId = row.id; showCreateAuthz = true">
                发起授权
              </el-button>
              <!-- M11 Phase 3：采访按钮只对被采访者本人可见 -->
              <el-button
                v-if="isSubjectSelf(row)"
                size="small"
                type="primary"
                :icon="ChatLineRound"
                :disabled="row.latestAuthStatus !== 'granted'"
                @click="onStartInterview(row)"
              >
                开始采访
              </el-button>
              <!-- 代答模式：匿名 subject（没 linkedUserId）由 userA 在管理员/发起人视角代为采访。
                   代答是高权力动作（产生的对话进入时间线、影响成稿），仅 admin/owner 可触发。 -->
              <el-button
                v-else-if="isAnonymousSubject(row) && canManage"
                size="small"
                type="primary"
                :icon="ChatLineRound"
                :disabled="row.latestAuthStatus !== 'granted'"
                @click="onStartInterview(row)"
              >
                代为采访
              </el-button>
              <!-- M11 Phase 3：其他人（非被采访者）只能看提示；不能代为开始 -->
              <el-tag
                v-else-if="row.linkedUserId && row.latestAuthStatus === 'granted'"
                size="small"
                type="info"
                effect="plain"
              >
                等待被采访者开始
              </el-tag>
              <!-- M11 Phase 3：编辑/删除按权限（编辑要 canEdit，删除要 canManage） -->
              <el-dropdown v-if="canEdit" trigger="click" @command="(c: string) => c === 'edit' ? onOpenEditSubject(row) : onDeleteSubject(row)">
                <el-button size="small" :icon="ArrowDown" />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="edit" :icon="Edit">编辑</el-dropdown-item>
                    <el-dropdown-item v-if="canManage" command="delete" :icon="Delete" divided>删除</el-dropdown-item>
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
              <!-- M11 Phase 3：撤销授权仅 admin 可见 -->
              <el-button
                v-if="canManage && ['pending', 'granted'].includes(row.status)"
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

    <!-- 添加人物对话框（M11 Phase 2：Tab 形式，支持从家族成员选 / 纯匿名） -->
    <el-dialog v-model="showAddSubject" title="添加被采访者" width="600px" @open="loadEligible">
      <el-alert
        v-if="eligibleMembers.length === 0 && !loadingEligible"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      >
        <template #title>当前项目无可选家族成员</template>
        家族管理员可在「家族 → 成员管理」添加成员；或用「匿名添加」模式手动创建。
      </el-alert>

      <el-tabs v-model="addSubjectTab">
        <!-- Tab 1：从家族成员选 -->
        <el-tab-pane :label="`从家族成员选 (${eligibleMembers.length})`" name="family">
          <template v-if="loadingEligible">
            <el-skeleton :rows="3" animated />
          </template>
          <template v-else-if="eligibleMembers.length > 0">
            <el-radio-group v-model="selectedFmId" style="width: 100%">
              <el-table :data="eligibleMembers" stripe @row-click="(row: EligibleFamilyMemberVO) => { if (!row.hasSubject) selectedFmId = row.familyMemberId }">
                <el-table-column width="50">
                  <template #default="{ row }">
                    <el-radio :value="row.familyMemberId" :disabled="row.hasSubject">
                      <span></span>
                    </el-radio>
                  </template>
                </el-table-column>
                <el-table-column label="姓名" width="160">
                  <template #default="{ row }">
                    <div class="fm__name">
                      <el-icon><UserFilled /></el-icon>
                      <span>{{ row.displayName }}</span>
                      <el-tag v-if="row.hasSubject" type="info" size="small" effect="plain">已添加</el-tag>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="账号" width="200">
                  <template #default="{ row }">
                    <span v-if="row.phone">{{ row.phone }}</span>
                    <span v-else-if="row.email">{{ row.email }}</span>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="角色" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" :type="(row.role === 'admin' ? 'warning' : row.role === 'viewer' ? 'info' : 'primary') as any">
                      {{ row.role === 'admin' ? '管理员' : row.role === 'editor' ? '编辑者' : '旁观者' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-radio-group>
            <el-form label-width="80px" style="margin-top: 16px">
              <el-form-item label="关系称呼">
                <el-input
                  v-model="familyMemberRelation"
                  placeholder="如：父亲、外婆、本人（选填）"
                  maxlength="32"
                />
              </el-form-item>
            </el-form>
            <el-alert type="info" :closable="false" show-icon style="margin-top: 8px">
              <template #title>采访家人</template>
              选中的家族成员会作为被采访者，TA 登录后能看到自己的采访内容。
            </el-alert>
          </template>
        </el-tab-pane>

        <!-- Tab 2：纯匿名添加（兼容老流程 / 老人无手机） -->
        <el-tab-pane label="匿名添加" name="anonymous">
          <el-form :model="anonymousForm" label-width="80px">
            <el-form-item label="姓名" required>
              <el-input v-model="anonymousForm.displayName" placeholder="如：爷爷、外婆" maxlength="64" show-word-limit />
            </el-form-item>
            <el-form-item label="关系">
              <el-input v-model="anonymousForm.relation" placeholder="如：父亲、外婆、本人" maxlength="32" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="anonymousForm.note" type="textarea" :rows="2" placeholder="选填，自己看的小抄" maxlength="512" show-word-limit />
            </el-form-item>
          </el-form>
          <el-alert type="warning" :closable="false" show-icon>
            <template #title>匿名被采访者</template>
            没有账号，授权链接只能通过一次性 token 发给 TA（适合老人无手机的场景）。
          </el-alert>
        </el-tab-pane>
      </el-tabs>

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

    <!-- 心声信箱（M13+）-->
    <section class="pd__heartcove">
      <div class="pd__heartcove-header">
        <span class="pd__heartcove-icon">📜</span>
        <span class="pd__heartcove-title">心声信箱</span>
        <el-tag v-if="heartcoveEntries.length" size="small" type="success" effect="plain">
          {{ heartcoveEntries.length }} 位已开启
        </el-tag>
      </div>
      <p class="pd__heartcove-desc">
        开启后，可与「他们」继续聊聊天。
        回应均由 AI 基于既往采访素材生成。
      </p>
      <ul class="pd__heartcove-list" v-if="subjects.length">
        <li v-for="s in subjects" :key="s.id" class="pd__heartcove-item">
          <span class="pd__heartcove-name">{{ s.displayName }}</span>
          <span class="pd__heartcove-state">
            <template v-if="heartcoveMap[s.id]?.enabled === 1">
              <el-tag size="small" type="success" effect="plain">已开启</el-tag>
              <el-button text size="small" type="primary" @click="enterHeartcove(s.id)">进入</el-button>
            </template>
            <template v-else>
              <span v-if="(heartcoveMap[s.id]?.turnsToGo ?? 0) > 0" class="pd__heartcove-hint">
                还差 {{ heartcoveMap[s.id]?.turnsToGo }} 轮采访
              </span>
              <el-button
                v-else
                size="small"
                type="primary"
                plain
                @click="openHeartcoveEnable(s.id, s.displayName)"
              >开启</el-button>
            </template>
          </span>
        </li>
      </ul>
      <el-empty v-else description="暂无可开启心信箱的人物" :image-size="60" />
    </section>

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

    <!-- M13+ 心声信箱开启对话框 -->
    <HeartcoveEnableDialog
      v-if="showHeartcoveEnable && heartcoveEnableTarget"
      :subject-id="heartcoveEnableTarget.id"
      :subject-name="heartcoveEnableTarget.name"
      @enabled="onHeartcoveEnabled"
      @cancel="showHeartcoveEnable = false"
    />
  </div>
</template>

<style scoped>
.pd { width: 100%; }
.pd__actions { margin-bottom: 12px; }
.muted { color: var(--mw-text-muted); }

/* M13+ 心声信箱 */
.pd__heartcove {
  margin-top: 24px;
  background: var(--mw-surface);
  border: 1px dashed var(--mw-border);
  border-radius: var(--mw-radius);
  padding: 16px 20px;
}
.pd__heartcove-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.pd__heartcove-icon { font-size: 18px; }
.pd__heartcove-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--mw-text);
  letter-spacing: 0.5px;
}
.pd__heartcove-desc {
  font-size: 12px;
  color: var(--mw-text-muted);
  margin: 0 0 12px;
}
.pd__heartcove-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.pd__heartcove-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--mw-bg);
  border-radius: 6px;
  font-size: 13px;
}
.pd__heartcove-name {
  color: var(--mw-text);
}
.pd__heartcove-state {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pd__heartcove-hint {
  font-size: 12px;
  color: var(--mw-text-muted);
}
.pd__subjName { display: inline-flex; align-items: center; gap: 6px; }
.fm__name { display: inline-flex; align-items: center; gap: 6px; }

/* M12+：个人项目极简 UI */
.pd__personal { max-width: 640px; margin: 24px auto; }
.pd__personal-card { padding: 16px 8px; text-align: center; }
.pd__personal-title { font-size: 22px; font-weight: 500; color: var(--mw-text, #1f2937); margin: 0 0 8px; }
.pd__personal-subtitle { color: var(--mw-text-muted, #6b7280); margin: 0 0 24px; line-height: 1.6; }
.pd__personal-loading { padding: 24px 0; }
.pd__personal-hint { margin-top: 16px; font-size: 13px; }
</style>
