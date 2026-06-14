<script setup lang="ts">
/**
 * 素材上传器（M3）。支持拖拽 + 选人物 + 进度条。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { smartUpload } from '@/utils/ossUpload'
import { listSubjects, type SubjectVO } from '@/api/subject'
import type { AssetVO } from '@/types/api'

const props = defineProps<{ projectId: string | number }>()
const emit = defineEmits<{ (e: 'uploaded', a: AssetVO): void }>()

const fileInput = ref<HTMLInputElement | null>(null)
const dragOver = ref(false)
const uploading = ref(false)
const progress = ref(0)
const subjects = ref<SubjectVO[]>([])
const subjectId = ref<number | null>(null)
const caption = ref('')

async function loadSubjects() {
  const { data } = await listSubjects(props.projectId)
  if (data?.code === 0) subjects.value = data.data || []
}

function pickFiles() {
  fileInput.value?.click()
}

async function handleFiles(files: FileList | File[] | null | undefined) {
  if (!files || (files as FileList).length === 0) return
  const list = Array.from(files as FileList)
  uploading.value = true
  progress.value = 0
  let ok = 0
  let fail = 0
  for (const f of list) {
    try {
      const a = await smartUpload(f, {
        projectId: props.projectId,
        subjectId: subjectId.value || undefined,
        caption: caption.value || undefined,
        onProgress: (loaded, total) => {
          if (total > 0) progress.value = Math.min(99, Math.round((loaded / total) * 100))
        },
      })
      ok++
      emit('uploaded', a)
    } catch (e: any) {
      fail++
      console.error(e)
      ElMessage.error(`${f.name}: ${e?.message || '上传失败'}`)
    }
  }
  uploading.value = false
  progress.value = 0
  if (ok > 0) {
    ElNotification.success({ title: '上传完成', message: `成功 ${ok} 个${fail ? `，失败 ${fail} 个` : ''}` })
    caption.value = ''
  }
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  dragOver.value = false
  handleFiles(e.dataTransfer?.files)
}

function onChange(e: Event) {
  const t = e.target as HTMLInputElement
  handleFiles(t.files)
  t.value = '' // 允许同一文件再次上传
}

onMounted(() => { loadSubjects() })
defineExpose({ loadSubjects })
</script>

<template>
  <div class="up">
    <div class="up__opts">
      <el-select
        v-model="subjectId"
        placeholder="关联人物（可选）"
        clearable
        size="default"
        style="width: 240px"
      >
        <el-option
          v-for="s in subjects"
          :key="s.id"
          :label="s.displayName + (s.relation ? `（${s.relation}）` : '')"
          :value="Number(s.id)"
        />
      </el-select>
      <el-input
        v-model="caption"
        placeholder="备注 / 图说（可选）"
        style="width: 280px"
      />
    </div>

    <div
      class="up__drop"
      :class="{ 'up__drop--over': dragOver, 'up__drop--busy': uploading }"
      @click="pickFiles"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop="onDrop"
    >
      <div v-if="!uploading" class="up__hint">
        <div class="up__icon">📁</div>
        <div>点击或拖拽图片 / 音频到此处上传</div>
        <div class="up__sub">支持 jpg/png/webp/gif、mp3/m4a/wav。单文件 ≤100MB</div>
      </div>
      <div v-else class="up__busy">
        <div>上传中…</div>
        <el-progress :percentage="progress" :stroke-width="14" />
      </div>
      <input ref="fileInput" type="file" multiple accept="image/*,audio/*" hidden @change="onChange" />
    </div>
  </div>
</template>

<style scoped>
.up { display: flex; flex-direction: column; gap: 12px; }
.up__opts { display: flex; gap: 12px; flex-wrap: wrap; }
.up__drop {
  border: 2px dashed #d1d5db;
  border-radius: 8px;
  padding: 40px 20px;
  text-align: center;
  cursor: pointer;
  background: #fff;
  transition: all 0.2s;
}
.up__drop--over { border-color: #2563eb; background: #eff6ff; }
.up__drop--busy { pointer-events: none; opacity: 0.85; }
.up__hint { color: #6b7280; }
.up__icon { font-size: 36px; margin-bottom: 8px; }
.up__sub { color: #9ca3af; font-size: 12px; margin-top: 6px; }
.up__busy { display: flex; flex-direction: column; gap: 12px; align-items: center; }
</style>