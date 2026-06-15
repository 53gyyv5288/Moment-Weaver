<script setup lang="ts">
/**
 * 模板选择弹窗 (M4)。
 * 用户选 template + scope + subjectIds → 触发 createDraft
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { SubjectVO } from '@/types/api'

interface TemplateOption {
  templateId: string
  scope: 'person' | 'family'
  name: string
  emoji: string
  desc: string
  sectionCount: number
}

const TEMPLATES: TemplateOption[] = [
  {
    templateId: 'person-template-v1',
    scope: 'person',
    name: '人物小传',
    emoji: '👤',
    desc: '单人物的章节化叙事：开篇 / 童年 / 家庭 / 成就 / 性格 / 结语',
    sectionCount: 6,
  },
  {
    templateId: 'family-template-v1',
    scope: 'family',
    name: '家族小传',
    emoji: '🏛️',
    desc: '跨人物的家族叙事：家族简介 / 家族渊源 / 关键时刻 / 价值观 / 展望',
    sectionCount: 5,
  },
]

const props = defineProps<{
  modelValue: boolean
  projectId: string | number
  subjects: SubjectVO[]
}>()

const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  'created': [draftId: string]
}>()

const selectedTemplate = ref<TemplateOption>(TEMPLATES[0])
// 用两个 ref 分别承接单选 / 多选，避免 el-select 在 multiple=false 时把 v-model 退化成 string
const singleSubjectId = ref<string>('')
const multiSubjectIds = ref<string[]>([])
const title = ref('')
const creating = ref(false)

const isPerson = computed(() => selectedTemplate.value.scope === 'person')

// 适配 el-select 的更新事件：单选时是 string，多选时是 string[]
function onSelectChange(v: string | string[] | undefined | null) {
  if (isPerson.value) {
    singleSubjectId.value = Array.isArray(v) ? (v[0] || '') : (v || '')
  } else {
    multiSubjectIds.value = Array.isArray(v) ? v.map(String).filter(Boolean) : []
  }
}

const canSubmit = computed(() => {
  if (creating.value) return false
  if (props.subjects.length === 0) return false
  if (isPerson.value) return singleSubjectId.value !== ''
  return multiSubjectIds.value.length >= 1
})

watch(() => props.modelValue, (v) => {
  if (v) {
    // 打开时重置
    selectedTemplate.value = TEMPLATES[0]
    singleSubjectId.value = ''
    multiSubjectIds.value = []
    title.value = ''
  }
})

watch(selectedTemplate, (t) => {
  if (t.scope === 'person' && multiSubjectIds.value.length > 1) {
    multiSubjectIds.value = multiSubjectIds.value.slice(0, 1)
  }
})

function close() {
  emit('update:modelValue', false)
}

async function onSubmit() {
  if (!canSubmit.value) {
    ElMessage.warning(isPerson.value ? '人物小传仅支持 1 位被采访者' : '至少选 1 位被采访者')
    return
  }
  if (props.subjects.length === 0) {
    ElMessage.warning('该项目下还没有被采访者，请先添加')
    return
  }
  // 规范化：保证后端拿到的是 string[]，不会因 el-select 单选回退成 string
  const subjectIds: string[] = isPerson.value
    ? (singleSubjectId.value ? [String(singleSubjectId.value)] : [])
    : (multiSubjectIds.value || []).map(String).filter(Boolean)
  if (subjectIds.length === 0) {
    ElMessage.warning('请选择被采访者')
    return
  }
  creating.value = true
  try {
    const { createDraft } = await import('@/api/draft')
    const { data } = await createDraft(props.projectId, {
      templateId: selectedTemplate.value.templateId,
      scope: selectedTemplate.value.scope,
      subjectIds,
      title: title.value.trim() || undefined,
    })
    if (data?.code === 0 && data.data) {
      ElMessage.success('已创建空成稿')
      emit('created', data.data.id)
      close()
    } else {
      ElMessage.error(data?.message || '创建失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '创建失败')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="emit('update:modelValue', $event)"
    title="选择成稿模板"
    width="640px"
    :close-on-click-modal="false"
  >
    <div class="tp">
      <div class="tp__group">
        <div class="tp__label">选择模板</div>
        <div class="tp__templates">
          <div
            v-for="t in TEMPLATES"
            :key="t.templateId"
            class="tp__tpl"
            :class="{ 'tp__tpl--active': selectedTemplate.templateId === t.templateId }"
            @click="selectedTemplate = t"
          >
            <div class="tp__tplHead">
              <span class="tp__emoji">{{ t.emoji }}</span>
              <span class="tp__tplName">{{ t.name }}</span>
              <el-tag size="small" effect="plain">
                {{ t.scope === 'person' ? '单人物' : '多人物' }}
              </el-tag>
            </div>
            <div class="tp__tplDesc">{{ t.desc }}</div>
            <div class="tp__tplMeta">共 {{ t.sectionCount }} 章</div>
          </div>
        </div>
      </div>

      <el-form label-width="100px" class="tp__form">
        <el-form-item :label="isPerson ? '被采访者' : '被采访者（多选）'">
          <el-select
            :model-value="isPerson ? singleSubjectId : multiSubjectIds"
            :multiple="!isPerson"
            :collapse-tags="!isPerson"
            placeholder="选择被采访者"
            style="width: 100%"
            :disabled="subjects.length === 0"
            @update:model-value="onSelectChange"
          >
            <el-option
              v-for="s in subjects"
              :key="s.id"
              :label="s.displayName + (s.relation ? '（' + s.relation + '）' : '')"
              :value="String(s.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标题（可选）">
          <el-input
            v-model="title"
            :placeholder="isPerson ? '如：父亲的一生' : '如：老王家的故事'"
            maxlength="60"
            show-word-limit
          />
        </el-form-item>
        <el-alert
          v-if="subjects.length === 0"
          type="warning"
          :closable="false"
          show-icon
        >
          <template #title>该项目还没有被采访者</template>
          请先在项目详情里添加人物。
        </el-alert>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
        >
          <template #title>创建的是「空」成稿</template>
          章节会先用模板的占位骨架填充，进入编辑页后再点「AI 生成」由 AI 填入内容。
        </el-alert>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="creating" :disabled="!canSubmit" @click="onSubmit">
        创建成稿
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tp { display: flex; flex-direction: column; gap: 16px; }
.tp__label { font-size: 13px; color: #4b5563; margin-bottom: 8px; font-weight: 500; }
.tp__templates { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.tp__tpl {
  border: 2px solid #e5e7eb;
  border-radius: 8px;
  padding: 14px;
  cursor: pointer;
  transition: all 0.15s;
}
.tp__tpl:hover { border-color: #93c5fd; }
.tp__tpl--active {
  border-color: #2563eb;
  background: #eff6ff;
}
.tp__tplHead {
  display: flex; align-items: center; gap: 8px; margin-bottom: 6px;
}
.tp__emoji { font-size: 20px; }
.tp__tplName { font-weight: 600; color: #1f2937; flex: 1; }
.tp__tplDesc { color: #6b7280; font-size: 12px; line-height: 1.5; }
.tp__tplMeta { color: #9ca3af; font-size: 11px; margin-top: 6px; }
.tp__form { margin-top: 4px; }
</style>
