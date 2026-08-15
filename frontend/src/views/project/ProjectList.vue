<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { FormInstance } from 'element-plus'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, View, Edit } from '@element-plus/icons-vue'
import { listProjects, deleteProject, updateProject } from '@/api/project'
import type { ProjectVO } from '@/api/project'
import { listFamilies, type FamilyVO } from '@/api/family'
import { formatDateTime } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const projects = ref<ProjectVO[]>([])
const familyMap = ref<Map<string, FamilyVO>>(new Map())

async function load() {
  loading.value = true
  try {
    const [proj, fam] = await Promise.all([
      listProjects({ page: 1, size: 50 }),
      listFamilies(),
    ])
    if (proj.data && proj.data.code === 0 && proj.data.data) {
      projects.value = proj.data.data.records
    }
    if (fam.data && fam.data.code === 0) {
      const m = new Map<string, FamilyVO>()
      ;(fam.data.data || []).forEach((f) => m.set(String(f.id), f))
      familyMap.value = m
    }
  } finally {
    loading.value = false
  }
}

function familyName(p: ProjectVO): FamilyVO | null {
  if (!p.familyId) return null
  return familyMap.value.get(String(p.familyId)) || null
}

async function handleDelete(p: ProjectVO) {
  try {
    await ElMessageBox.confirm(
      `确定删除项目「${p.name}」吗？30 天内可在「合规中心 → 回收站」恢复。`,
      '删除确认',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  const { data } = await deleteProject(p.id)
  if (data && data.code === 0) {
    ElMessage.success('已删除')
    load()
  }
}

function typeLabel(t: string) {
  return t === 'family' ? '家族' : t === 'personal' ? '个人' : t
}

// ---- 编辑项目 ----
const editingProject = ref<ProjectVO | null>(null)
const editForm = ref({ name: '', description: '' })
const editRules = {
  name: [
    { required: true, message: '请输入项目名', trigger: 'blur' },
    { min: 1, max: 128, message: '1-128 字', trigger: 'blur' },
  ],
  description: [{ max: 512, message: '最多 512 字', trigger: 'blur' }],
}
const editFormRef = ref<FormInstance>()
const showEdit = ref(false)
const savingEdit = ref(false)

function onOpenEdit(p: ProjectVO) {
  editingProject.value = p
  editForm.value = { name: p.name, description: p.description ?? '' }
  showEdit.value = true
}

async function onSaveEdit() {
  if (!editingProject.value || !editFormRef.value) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return
  const newName = editForm.value.name.trim()
  const newDesc = editForm.value.description.trim()
  const origName = editingProject.value.name
  const origDesc = editingProject.value.description ?? ''
  if (newName === origName && newDesc === origDesc) {
    ElMessage.warning('没有修改任何字段')
    return
  }
  // 后端 @AssertTrue 要求至少一个字段改变；同值字段不传
  const data: { name?: string; description?: string } = {}
  if (newName !== origName) data.name = newName
  if (newDesc !== origDesc) data.description = newDesc || ''
  savingEdit.value = true
  try {
    const { data: resp } = await updateProject(editingProject.value.id, data)
    if (resp && resp.code === 0) {
      ElMessage.success('已保存')
      showEdit.value = false
      editingProject.value = null
      await load()
    } else {
      ElMessage.error(resp?.message || '保存失败')
    }
  } finally {
    savingEdit.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="projects">
    <div class="projects__head">
      <div>
        <h2 class="projects__title">我的项目</h2>
        <p class="projects__lead">
          每个项目是一个人或一个家族的记忆容器：添加被采访者 → 发起授权 → AI 采访 → 生成成稿。
        </p>
      </div>
      <el-button type="primary" :icon="Plus" @click="router.push('/projects/new')">
        新建项目
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />

    <el-empty
      v-else-if="!projects.length"
      description="还没有项目，从新建第一个开始吧"
    >
      <el-button type="primary" :icon="Plus" @click="router.push('/projects/new')">
        新建项目
      </el-button>
    </el-empty>

    <template v-else>
      <div class="projects__count">共 {{ projects.length }} 个项目</div>
      <div class="projects__grid">
        <article
          v-for="p in projects"
          :key="p.id"
          class="mw-card projects__card"
          @click="router.push(`/projects/${p.id}`)"
        >
          <div class="projects__cardHead">
            <div class="projects__tags">
              <el-tag
                size="small"
                round
                :type="p.type === 'family' ? 'success' : 'info'"
                effect="light"
              >
                {{ typeLabel(p.type) }}
              </el-tag>
              <el-tag
                v-if="familyName(p)"
                size="small"
                round
                type="warning"
                effect="plain"
                @click.stop="router.push(`/families/${familyName(p)!.id}`)"
              >
                🏠 {{ familyName(p)!.name }}
              </el-tag>
              <el-tag v-else size="small" round effect="plain">个人</el-tag>
            </div>
            <span class="projects__time">{{ formatDateTime(p.createdAt) }}</span>
          </div>

          <h3 class="projects__name">{{ p.name }}</h3>
          <p class="projects__desc">{{ p.description || '暂无描述' }}</p>

          <div class="projects__foot">
            <el-button size="small" text type="primary" :icon="View" @click.stop="router.push(`/projects/${p.id}`)">
              进入
            </el-button>
            <el-button size="small" text type="primary" :icon="Edit" @click.stop="onOpenEdit(p)">
              编辑
            </el-button>
            <el-button size="small" text type="danger" :icon="Delete" @click.stop="handleDelete(p)">
              删除
            </el-button>
          </div>
        </article>
      </div>
    </template>

    <!-- 编辑项目对话框 -->
    <el-dialog v-model="showEdit" title="编辑项目" width="500px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-form-item label="项目名" prop="name">
          <el-input v-model="editForm.name" placeholder="1-128 字" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="3"
            placeholder="一句话介绍这个项目（留空可清空）"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEdit = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" @click="onSaveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.projects__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}
.projects__title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--mw-text);
}
.projects__lead {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--mw-text-secondary);
  line-height: 1.6;
}
.projects__count {
  font-size: 12px;
  color: var(--mw-text-muted);
  margin-bottom: 12px;
}
.projects__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.projects__card {
  padding: 18px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: border-color 0.15s, box-shadow 0.15s, transform 0.15s;
}
.projects__card:hover {
  border-color: var(--mw-primary);
  box-shadow: var(--mw-shadow-hover);
  transform: translateY(-2px);
}
.projects__cardHead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.projects__tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.projects__time {
  font-size: 12px;
  color: var(--mw-text-muted);
}
.projects__name {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--mw-text);
  line-height: 1.4;
  word-break: break-all;
}
.projects__desc {
  margin: 0;
  font-size: 13px;
  color: var(--mw-text-secondary);
  line-height: 1.6;
  min-height: 42px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.projects__foot {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  padding-top: 10px;
  border-top: 1px dashed var(--mw-border);
}
</style>
