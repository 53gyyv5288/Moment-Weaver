<script setup lang="ts">
/**
 * 家族详情 + 成员管理（M10+ Family Phase 1）。
 * 路由：/families/:id
 *
 * <p>三种角色权限：
 *   <ul>
 *     <li>admin  —— 可邀请 / 移除 / 改角色 / 重置密码</li>
 *     <li>editor —— 可在家族下创建/编辑项目（在项目页操作，本页不可）</li>
 *     <li>viewer —— 只读</li>
 *   </ul>
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
} from 'element-plus'
import {
  Plus,
  User,
  Edit,
  Delete,
  Refresh,
} from '@element-plus/icons-vue'
import {
  adminCreateMember,
  getFamily,
  listFamilyMembers,
  listFamilyProjects,
  removeFamilyMember,
  updateFamilyMember,
  type AdminCreateUserResponse,
  type FamilyMemberVO,
  type FamilyRole,
  type FamilyVO,
  type UpdateFamilyMemberRequest,
} from '@/api/family'
import type { ProjectVO } from '@/api/project'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const familyId = computed(() => route.params.id as string)

const family = ref<FamilyVO | null>(null)
const members = ref<FamilyMemberVO[]>([])
const projects = ref<ProjectVO[]>([])
const loading = ref(false)

const isAdmin = computed(() => family.value?.myRole === 'admin')
const activeTab = ref('overview')

// ===== 创建成员弹窗 =====
const showCreate = ref(false)
const createFormRef = ref<FormInstance>()
const creating = ref(false)
const createdResult = ref<AdminCreateUserResponse | null>(null)
const createForm = ref({
  displayName: '',
  phone: '',
  email: '',
  password: '',
  role: 'editor' as FamilyRole,
})

const createRules: FormRules = {
  displayName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
    { min: 1, max: 64, message: '1-64 字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 64, message: '8-64 位', trigger: 'blur' },
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
}

function resetCreateForm() {
  createForm.value = {
    displayName: '',
    phone: '',
    email: '',
    password: '',
    role: 'editor',
  }
  createdResult.value = null
}

async function onCreateMember() {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (!createForm.value.phone && !createForm.value.email) {
    ElMessage.warning('手机号和邮箱至少填一个')
    return
  }
  creating.value = true
  try {
    const { data } = await adminCreateMember(familyId.value, {
      displayName: createForm.value.displayName.trim(),
      phone: createForm.value.phone.trim() || undefined,
      email: createForm.value.email.trim() || undefined,
      password: createForm.value.password,
      role: createForm.value.role,
    })
    if (data?.code === 0 && data.data) {
      createdResult.value = data.data
      ElMessage.success('已创建，请把账号密码告知该成员')
      await load()
    } else {
      ElMessage.error(data?.message || '创建失败')
    }
  } finally {
    creating.value = false
  }
}

function closeCreate() {
  showCreate.value = false
  resetCreateForm()
}

// ===== 编辑成员 =====
const editingMember = ref<FamilyMemberVO | null>(null)
const editForm = ref({ role: 'editor' as FamilyRole, resetPassword: '' })
const editDialog = ref(false)
const savingEdit = ref(false)

function openEditMember(m: FamilyMemberVO) {
  editingMember.value = m
  editForm.value = { role: m.role, resetPassword: '' }
  editDialog.value = true
}

async function onSaveMember() {
  if (!editingMember.value) return
  savingEdit.value = true
  try {
    const payload: UpdateFamilyMemberRequest = { role: editForm.value.role }
    if (editForm.value.resetPassword && editForm.value.resetPassword.length >= 8) {
      payload.resetPassword = editForm.value.resetPassword
    }
    const { data } = await updateFamilyMember(
      familyId.value,
      editingMember.value.userId,
      payload,
    )
    if (data?.code === 0) {
      ElMessage.success('已更新')
      editDialog.value = false
      await load()
    } else {
      ElMessage.error(data?.message || '更新失败')
    }
  } finally {
    savingEdit.value = false
  }
}

async function onRemoveMember(m: FamilyMemberVO) {
  try {
    await ElMessageBox.confirm(
      `确定移除成员「${m.displayName}」？该成员的账号不会被删除，只是脱离家族。`,
      '确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  const { data } = await removeFamilyMember(familyId.value, m.userId)
  if (data?.code === 0) {
    ElMessage.success('已移除')
    await load()
  } else {
    ElMessage.error(data?.message || '移除失败')
  }
}

// ===== 数据加载 =====
async function load() {
  loading.value = true
  try {
    const [f, m, p] = await Promise.all([
      getFamily(familyId.value),
      listFamilyMembers(familyId.value),
      listFamilyProjects(familyId.value),
    ])
    if (f.data?.code === 0) family.value = f.data.data
    else ElMessage.error(f.data?.message || '家族加载失败')
    if (m.data?.code === 0) members.value = m.data.data || []
    if (p.data?.code === 0) projects.value = p.data.data || []
  } finally {
    loading.value = false
  }
}

function roleLabel(r: string) {
  return { admin: '管理员', editor: '编辑者', viewer: '旁观者' }[r] || r
}
function roleType(r: string) {
  return { admin: 'warning', editor: '', viewer: 'info' }[r] || ''
}

function statusLabel(s: number) {
  return { 1: '进行中', 0: '已归档' }[s] || String(s)
}

function onCreateProject() {
  router.push({ path: '/projects/new', query: { familyId: familyId.value } })
}

onMounted(load)
</script>

<template>
  <div class="fd" v-loading="loading">
    <!-- 头部（家族名 + 角色徽章 + 成员数） -->
    <header class="fd__head">
      <el-button text @click="router.push('/families')">← 家族列表</el-button>
      <div class="fd__headMain">
        <div class="fd__headTitle">
          <h2>{{ family?.name || '家族' }}</h2>
          <el-tag v-if="family" :type="roleType(family.myRole) as any" size="small" effect="plain">
            您的角色：{{ roleLabel(family.myRole) }}
          </el-tag>
        </div>
        <p v-if="family?.description" class="muted">{{ family.description }}</p>
      </div>
    </header>

    <el-tabs v-model="activeTab" class="fd__tabs">
      <!-- ============== 概览 ============== -->
      <el-tab-pane label="概览" name="overview">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="家族 ID">{{ family?.id }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ family?.createdAt ? formatDateTime(family.createdAt) : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="成员数">{{ family?.memberCount ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="项目数">{{ projects.length }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <!-- ============== 成员管理 ============== -->
      <el-tab-pane :label="`成员 (${members.length})`" name="members">
        <div v-if="isAdmin" class="fd__actions">
          <el-button type="primary" :icon="Plus" @click="showCreate = true">
            创建成员账号
          </el-button>
        </div>

        <el-empty v-if="members.length === 0" description="还没有成员" />
        <el-table v-else :data="members" stripe>
          <el-table-column label="姓名" width="160">
            <template #default="{ row }">
              <div class="fd__memberName">
                <el-icon><User /></el-icon>
                <span>{{ row.displayName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="账号" width="240">
            <template #default="{ row }">
              <span v-if="row.phone">{{ row.phone }}</span>
              <span v-else-if="row.email">{{ row.email }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="120">
            <template #default="{ row }">
              <el-tag :type="roleType(row.role) as any" size="small">
                {{ roleLabel(row.role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="加入时间">
            <template #default="{ row }">
              {{ formatDateTime(row.joinedAt) }}
            </template>
          </el-table-column>
          <el-table-column v-if="isAdmin" label="操作" width="220" align="right" fixed="right">
            <template #default="scope">
              <el-button size="small" :icon="Edit" @click="openEditMember(scope.row as FamilyMemberVO)">
                编辑
              </el-button>
              <el-button
                size="small"
                type="danger"
                :icon="Delete"
                @click="onRemoveMember(scope.row as FamilyMemberVO)"
              >
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============== 家族项目 ============== -->
      <el-tab-pane :label="`家族项目 (${projects.length})`" name="projects">
        <div v-if="family?.myRole !== 'viewer'" class="fd__actions">
          <el-button type="primary" :icon="Plus" @click="onCreateProject">
            在家族下创建项目
          </el-button>
        </div>

        <el-empty v-if="projects.length === 0" description="家族下还没有项目" />
        <div v-else class="fd__projects">
          <el-card
            v-for="p in projects"
            :key="p.id"
            shadow="hover"
            class="fd__projectCard"
            @click="router.push(`/projects/${p.id}`)"
          >
            <header class="fd__projectHead">
              <h4>{{ p.name }}</h4>
              <el-tag size="small" effect="plain">{{ p.type === 'family' ? '家族小传' : '个人小传' }}</el-tag>
            </header>
            <p v-if="p.description" class="fd__projectDesc">{{ p.description }}</p>
            <p v-else class="muted">（无描述）</p>
            <footer class="fd__projectMeta">
              <span>状态：{{ statusLabel(p.status) }}</span>
              <span>{{ formatDateTime(p.updatedAt) }}</span>
            </footer>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ===== 创建成员弹窗 ===== -->
    <el-dialog
      v-model="showCreate"
      title="创建家族成员账号"
      width="540px"
      :close-on-click-modal="false"
      @close="resetCreateForm"
    >
      <template v-if="!createdResult">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 16px"
        >
          <template #title>管理员代创建账号</template>
          该成员登录后会被强制要求改密。请把生成的初始密码告知本人。
        </el-alert>

        <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
          <el-form-item label="姓名" prop="displayName" required>
            <el-input v-model="createForm.displayName" placeholder="如：张三" maxlength="64" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="createForm.phone" placeholder="选填；和邮箱至少填一个" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="createForm.email" placeholder="选填" />
          </el-form-item>
          <el-form-item label="初始密码" prop="password" required>
            <el-input
              v-model="createForm.password"
              type="password"
              show-password
              placeholder="8-64 位，登录后会被强制改密"
            />
          </el-form-item>
          <el-form-item label="角色" prop="role" required>
            <el-radio-group v-model="createForm.role">
              <el-radio value="editor">编辑者（推荐）</el-radio>
              <el-radio value="viewer">旁观者（只读）</el-radio>
              <el-radio value="admin">管理员</el-radio>
            </el-radio-group>
            <div class="muted" style="margin-top: 4px">
              编辑者可采访/生成成稿；旁观者只能查看不能编辑
            </div>
          </el-form-item>
        </el-form>
      </template>

      <template v-else>
        <el-result icon="success" title="成员账号已创建">
          <template #sub-title>
            <div class="fd__createdInfo">
              <p>姓名：<strong>{{ createdResult.displayName }}</strong></p>
              <p>角色：{{ roleLabel(createdResult.role) }}</p>
              <p>
                初始密码：<strong style="color: #d97706">{{ createdResult.initialPassword }}</strong>
              </p>
              <el-alert type="warning" :closable="false" show-icon style="margin-top: 12px">
                <template #title>请把以上账号密码告知该成员</template>
                该成员首次登录后会被强制修改密码。密码只在本次显示，关闭后无法再次查看。
              </el-alert>
            </div>
          </template>
        </el-result>
      </template>

      <template #footer>
        <template v-if="!createdResult">
          <el-button @click="closeCreate">取消</el-button>
          <el-button type="primary" :loading="creating" @click="onCreateMember">
            创建
          </el-button>
        </template>
        <template v-else>
          <el-button type="primary" @click="closeCreate">我已抄送，关闭</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- ===== 编辑成员弹窗 ===== -->
    <el-dialog v-model="editDialog" title="编辑成员" width="460px">
      <el-form v-if="editingMember" label-width="100px">
        <el-form-item label="姓名">
          <span>{{ editingMember.displayName }}</span>
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="editForm.role">
            <el-radio value="admin">管理员</el-radio>
            <el-radio value="editor">编辑者</el-radio>
            <el-radio value="viewer">旁观者</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="重置密码">
          <el-input
            v-model="editForm.resetPassword"
            type="password"
            show-password
            placeholder="留空则不重置"
          />
          <div class="muted" style="margin-top: 4px">
            填写后该成员下次登录必须改密（8-64 位）
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" :icon="Refresh" @click="onSaveMember">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.fd { width: 100%; }
.fd__head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ece4d9;
}
.fd__headMain { flex: 1; min-width: 0; }
.fd__headTitle { display: flex; align-items: center; gap: 12px; }
.fd__headTitle h2 { margin: 0; }
.muted { color: #9ca3af; font-size: 13px; margin: 4px 0 0; }
.fd__actions { margin-bottom: 12px; }
.fd__memberName { display: inline-flex; align-items: center; gap: 6px; }
.fd__projects {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.fd__projectCard { cursor: pointer; }
.fd__projectHead {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.fd__projectHead h4 { margin: 0; font-size: 16px; }
.fd__projectDesc {
  font-size: 13px;
  color: #6b7280;
  margin: 0 0 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.fd__projectMeta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #9ca3af;
  border-top: 1px solid #f3f4f6;
  padding-top: 8px;
}
.fd__createdInfo p { margin: 6px 0; font-size: 14px; }
</style>
