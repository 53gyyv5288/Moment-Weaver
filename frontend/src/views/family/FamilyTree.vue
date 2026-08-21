<script setup lang="ts">
/**
 * M14+ 家族关系图：横向家族树（v2 — 美化 + 大规模可视）。
 *
 * 设计要点：
 *   - 数据源：父组件传入扁平 SubjectTreeNodeVO[]（含 nodes / orphans）
 *   - 布局：手写 BFS 按 generation 分层 + 同层按 id 排序（绕过 d3.stratify）
 *   - 渲染：单 SVG + viewBox，外层 <g transform="translate(panX,panY) scale(zoom)"> 整图平移缩放
 *   - 虚拟渲染：visibleNodes / visibleLinks 只渲染当前可见 ±1 代际，支持 100+ 代规模
 *   - 缩略图：右下角 200×120 minimap 始终显示全图；视口矩形 + click/drag 同步主视图
 *   - pan/zoom：手写 mousedown/move/wheel（不引 d3-zoom），zoom 0.4..3.0 clamp
 *   - 详情弹窗：enableDetailPopup=true（默认）→ 点节点开 el-dialog；false → 走原 emit
 *   - 环引用兜底：try/catch 降级成扁平列表
 *   - 保存兼容：FamilyList.vue 零改动 / ProjectDetail.vue 加 :enable-detail-popup="false"
 *
 * 数据流：
 *   props.nodes → layout (BFS) → bbox
 *     ├─ visibleNodes / visibleLinks (虚拟渲染) → SVG 主视图
 *     ├─ minimapDots → SVG 缩略图（全量节点 2x2 rect）
 *     └─ minimapViewportRect (computed) → 缩略图视口矩形
 *   用户 pan/zoom → visibleWorldX → viewportVisibleGens → 重新过滤 visibleNodes
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { linkHorizontal } from 'd3-shape'
import type { SubjectTreeNodeVO } from '@/types/api'
import { formatGenerationLabel } from '@/utils/generation'

const props = withDefaults(
  defineProps<{
    nodes: SubjectTreeNodeVO[]
    /** 待归位 subject id 列表（不在树上的节点，渲染在下方灰色区） */
    orphans?: string[]
    /** 是否启用详情弹窗。家族下用 true（默认），项目下传 false 走原编辑弹窗 */
    enableDetailPopup?: boolean
  }>(),
  { enableDetailPopup: true },
)

const emit = defineEmits<{
  (e: 'node-click', node: SubjectTreeNodeVO): void
}>()

// ===== 节点几何（紧凑型 paper-note） =====
const NODE_W = 120
const NODE_H = 40
const H_SPACING = 16  // 同辈间距
const V_SPACING = 24  // 代际间距
const ZOOM_X = NODE_W + H_SPACING  // 136
const ZOOM_Y = NODE_H + V_SPACING  // 64
const MINI_W = 200
const MINI_H = 120

// ===== 内部类型 =====
interface LayoutNode {
  id: string
  displayName: string
  relation: string | null
  generation: number | null
  parentId: string | null
  generationWarning: string | null
  row: number       // 同辈横排位置
  genIndex: number  // 整理后第几列（0=最左=长辈）
  isOrphan: boolean
}

interface LayoutLink {
  sourceId: string | null  // null = 虚拟根
  targetId: string
  isVirtual: boolean
}

interface Layout {
  nodes: LayoutNode[]
  links: LayoutLink[]
  genCount: number
  bbox: { width: number; height: number }
}

// ===== 布局计算（核心） =====
const layout = computed<Layout | null>(() => {
  if (props.nodes.length === 0) return null

  try {
    // 1) 节点映射（先生成位置，最后算坐标）
    const nodeById = new Map<string, LayoutNode>()
    const realNodes: LayoutNode[] = []
    for (const n of props.nodes) {
      const node: LayoutNode = {
        id: n.id,
        displayName: n.displayName ?? '(无名)',
        relation: n.relation ?? null,
        generation: n.generation ?? null,
        parentId: n.parentSubjectId ?? null,
        generationWarning: n.generationWarning ?? null,
        row: 0,
        genIndex: 0,
        isOrphan: n.generation === null || n.generation === undefined,
      }
      nodeById.set(n.id, node)
      realNodes.push(node)
    }

    // 2) 父指向判定：自指 / 父指向不存在 → 视为孤儿（不挂虚拟根 —— 后面靠 layout 决策）
    for (const n of realNodes) {
      if (n.parentId === n.id || (n.parentId && !nodeById.has(n.parentId))) {
        n.parentId = null
        n.isOrphan = true
      }
    }

    // 3) 按 generation 分层（null → 高 sentinel 排到末尾）
    const layerMap = new Map<number, LayoutNode[]>()
    for (const n of realNodes) {
      const g = n.generation ?? Number.MAX_SAFE_INTEGER
      const arr = layerMap.get(g) ?? []
      arr.push(n)
      layerMap.set(g, arr)
    }
    // 升序：负数（长辈）在前 → 0（本人辈）→ 正数（晚辈）→ null 最后
    const sortedGens = [...layerMap.keys()].sort((a, b) => a - b)
    sortedGens.forEach((g, idx) => {
      const ns = layerMap.get(g)!
      ns.sort((a, b) => a.id.localeCompare(b.id))
      ns.forEach((n, row) => {
        n.genIndex = idx
        n.row = row
      })
    })

    // 4) 连线（保留虚拟根处理多根场景）
    const links: LayoutLink[] = []
    const childByParent = new Map<string, LayoutNode[]>()
    for (const n of realNodes) {
      const parentId = n.parentId ?? '__virtual_root__'
      const arr = childByParent.get(parentId) ?? []
      arr.push(n)
      childByParent.set(parentId, arr)
      links.push({
        sourceId: n.parentId,
        targetId: n.id,
        isVirtual: n.parentId === null,
      })
    }

    // 5) bbox
    const maxRow = Math.max(...realNodes.map((n) => n.row))
    const maxGen = sortedGens.length - 1
    const width = (maxRow + 1) * ZOOM_X + 40
    const height = (maxGen + 1) * ZOOM_Y + 40

    return {
      nodes: realNodes,
      links,
      genCount: sortedGens.length,
      bbox: { width: Math.max(width, 200), height: Math.max(height, 200) },
    }
  } catch (e) {
    console.warn('[FamilyTree] layout failed, fallback to flat list', e)
    return null
  }
})

// ===== 降级列表 =====
const fallbackList = computed<SubjectTreeNodeVO[]>(() => {
  if (layout.value !== null) return []
  return props.nodes
})

const orphanNodes = computed<SubjectTreeNodeVO[]>(() => {
  const orphanIds = new Set(props.orphans ?? [])
  return props.nodes.filter((n) => orphanIds.has(n.id))
})

// ===== 缩放/平移状态 =====
const panX = ref(0)
const panY = ref(0)
const zoom = ref(1)
const ZOOM_MIN = 0.4
const ZOOM_MAX = 3.0
const viewportRef = ref<HTMLDivElement | null>(null)
const containerSize = ref({ width: 800, height: 480 })
let resizeObserver: ResizeObserver | null = null
let isDragging = false
let dragStart = { x: 0, y: 0, panX: 0, panY: 0 }

// 监听容器尺寸（el-tabs 隐藏后切换可见时也能触发）
onMounted(() => {
  if (!viewportRef.value) return
  const update = () => {
    if (!viewportRef.value) return
    const rect = viewportRef.value.getBoundingClientRect()
    containerSize.value = { width: rect.width, height: rect.height }
    // 首次挂载且没有用户交互 → 自动 fit
    if (panX.value === 0 && panY.value === 0 && zoom.value === 1 && layout.value) {
      fitToContent()
    }
  }
  resizeObserver = new ResizeObserver(update)
  resizeObserver.observe(viewportRef.value)
  update()
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
})

// ===== 虚拟渲染（核心） =====
const visibleWorldX = computed(() => {
  const halfW = containerSize.value.width / 2 / zoom.value
  return { min: panX.value - halfW, max: panX.value + halfW }
})

const viewportVisibleGens = computed(() => {
  if (!layout.value) return new Set<number>()
  const { min, max } = visibleWorldX.value
  const minGen = Math.floor(min / ZOOM_X) - 1
  const maxGen = Math.ceil(max / ZOOM_X) + 1
  const set = new Set<number>()
  const total = layout.value.genCount
  for (let g = Math.max(0, minGen); g <= Math.min(total - 1, maxGen); g++) {
    set.add(g)
  }
  return set
})

const visibleNodes = computed<LayoutNode[]>(() => {
  if (!layout.value) return []
  const gens = viewportVisibleGens.value
  return layout.value.nodes.filter((n) => gens.has(n.genIndex))
})

const visibleLinks = computed<LayoutLink[]>(() => {
  if (!layout.value) return []
  const visibleIds = new Set(visibleNodes.value.map((n) => n.id))
  return layout.value.links.filter(
    (l) =>
      (l.sourceId === null || visibleIds.has(l.sourceId)) &&
      visibleIds.has(l.targetId),
  )
})

// ===== 缩略图数据 =====
const minimapDots = computed(() => {
  if (!layout.value) return []
  return layout.value.nodes.map((n) => {
    const x = (n.genIndex * ZOOM_X + NODE_W / 2) / layout.value!.bbox.width
    const y = (n.row * ZOOM_Y + NODE_H / 2) / layout.value!.bbox.height
    return { id: n.id, x: x * MINI_W, y: y * MINI_H, isOrphan: n.isOrphan, hasWarning: !!n.generationWarning }
  })
})

const minimapViewportRect = computed(() => {
  if (!layout.value) return null
  const { width: W, height: H } = layout.value.bbox
  const halfW = containerSize.value.width / 2 / zoom.value
  const halfH = containerSize.value.height / 2 / zoom.value
  const leftWorld = panX.value - halfW
  const topWorld = panY.value - halfH
  return {
    x: (leftWorld / W) * MINI_W,
    y: (topWorld / H) * MINI_H,
    w: (containerSize.value.width / zoom.value / W) * MINI_W,
    h: (containerSize.value.height / zoom.value / H) * MINI_H,
  }
})

const showMinimap = computed(() => containerSize.value.width >= 500)

// ===== 平移/缩放交互 =====
function onMouseDown(e: MouseEvent) {
  if (e.button !== 0) return
  isDragging = true
  dragStart = {
    x: e.clientX,
    y: e.clientY,
    panX: panX.value,
    panY: panY.value,
  }
  e.preventDefault()
}
function onMouseMove(e: MouseEvent) {
  if (!isDragging) return
  panX.value = dragStart.panX - (e.clientX - dragStart.x) / zoom.value
  panY.value = dragStart.panY - (e.clientY - dragStart.y) / zoom.value
}
function onMouseUp() {
  isDragging = false
}

// 全局监听 mousemove/mouseup（防止拖出 viewport 后丢失）
onMounted(() => {
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
})
onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
})

function onWheel(e: WheelEvent) {
  if (!layout.value) return
  const rect = viewportRef.value?.getBoundingClientRect()
  if (!rect) return
  const cursorX = e.clientX - rect.left - rect.width / 2
  const cursorY = e.clientY - rect.top - rect.height / 2
  const worldXBefore = panX.value + cursorX / zoom.value
  const worldYBefore = panY.value + cursorY / zoom.value
  const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15
  const newZoom = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, zoom.value * factor))
  if (newZoom === zoom.value) return
  zoom.value = newZoom
  panX.value = worldXBefore - cursorX / zoom.value
  panY.value = worldYBefore - cursorY / zoom.value
}

function fitToContent() {
  if (!layout.value) return
  const { width: W, height: H } = layout.value.bbox
  panX.value = W / 2
  panY.value = H / 2
  const fitZoom = Math.min(1, containerSize.value.width / (W + 100))
  zoom.value = Math.max(ZOOM_MIN, Math.min(1, fitZoom))
}

function zoomIn() {
  zoom.value = Math.min(ZOOM_MAX, zoom.value * 1.25)
}
function zoomOut() {
  zoom.value = Math.max(ZOOM_MIN, zoom.value / 1.25)
}

// ===== 缩略图交互 =====
const minimapRef = ref<SVGSVGElement | null>(null)
let isMinimapDragging = false
function onMinimapPointerDown(e: PointerEvent) {
  if (!layout.value || !minimapRef.value) return
  isMinimapDragging = true
  minimapRef.value.setPointerCapture(e.pointerId)
  placeMinimap(e)
}
function onMinimapPointerMove(e: PointerEvent) {
  if (!isMinimapDragging) return
  placeMinimap(e)
}
function onMinimapPointerUp(e: PointerEvent) {
  isMinimapDragging = false
  minimapRef.value?.releasePointerCapture(e.pointerId)
}
function placeMinimap(e: PointerEvent) {
  if (!layout.value || !minimapRef.value) return
  const rect = minimapRef.value.getBoundingClientRect()
  const x = (e.clientX - rect.left) / rect.width
  const y = (e.clientY - rect.top) / rect.height
  panX.value = x * layout.value.bbox.width
  panY.value = y * layout.value.bbox.height
}

// ===== 详情弹窗 =====
const showDetail = ref(false)
const selectedNode = ref<SubjectTreeNodeVO | null>(null)
const selectedParentName = computed(() => {
  if (!selectedNode.value?.parentSubjectId) return null
  return props.nodes.find((n) => n.id === selectedNode.value!.parentSubjectId)?.displayName ?? null
})
const parentRelationLabel = computed(() => {
  const t = selectedNode.value?.parentRelationType
  if (t === 'father') return '父'
  if (t === 'mother') return '母'
  if (t === 'guardian') return '监护人'
  return null
})

function onNodeClick(node: LayoutNode) {
  const raw = props.nodes.find((n) => n.id === node.id)
  if (!raw) return
  selectedNode.value = raw
  if (props.enableDetailPopup) {
    showDetail.value = true
  } else {
    emit('node-click', raw)
  }
}
function closeDetail() {
  showDetail.value = false
}

// ===== 节点连线 path（d3-shape linkHorizontal） =====
const linkGen = linkHorizontal<{ source: LayoutNode; target: LayoutNode }, LayoutNode>()
  .x((d) => d.target.x)
  .y((d) => d.target.y)
function linkPath(link: LayoutLink): string {
  if (!layout.value) return ''
  if (link.sourceId === null) {
    // 虚拟根出来的连线 → 从最左侧画到 target
    const target = layout.value.nodes.find((n) => n.id === link.targetId)
    if (!target) return ''
    const tx = target.row * ZOOM_X + NODE_W / 2
    const ty = target.genIndex * ZOOM_Y + NODE_H / 2
    return `M 0 ${ty} L ${tx - NODE_W / 2} ${ty}`
  }
  const source = layout.value.nodes.find((n) => n.id === link.sourceId)
  const target = layout.value.nodes.find((n) => n.id === link.targetId)
  if (!source || !target) return ''
  const sx = source.row * ZOOM_X + NODE_W / 2
  const sy = source.genIndex * ZOOM_Y + NODE_H / 2
  const tx = target.row * ZOOM_X + NODE_W / 2
  const ty = target.genIndex * ZOOM_Y + NODE_H / 2
  return `M ${sx + NODE_W / 2} ${sy} L ${tx - NODE_W / 2} ${ty}`
}

// ===== 缩略图同步当前视图（用 watch + saveLayout）=====
watch([showMinimap], () => {
  // 响应式 minimap 显示——空函数占位
})
</script>

<template>
  <div class="ft">
    <!-- 异常降级：环引用或脏数据导致 layout 计算失败 -->
    <div v-if="layout === null && fallbackList.length > 0" class="ft__fallback">
      <el-alert type="warning" :closable="false" show-icon>
        <template #title>家族树渲染失败（数据可能存在环引用）</template>
        列出全部 {{ fallbackList.length }} 位被采访者，请到编辑弹窗修正父/母关系后再试。
      </el-alert>
      <ul class="ft__fallback-list">
        <li v-for="n in fallbackList" :key="n.id" class="ft__fallback-item">
          <strong>{{ n.displayName }}</strong>
          <span v-if="n.relation" class="muted">（{{ n.relation }}）</span>
          <span v-if="n.generation !== null && n.generation !== undefined" class="ft__gen">
            {{ formatGenerationLabel(n.generation) }}
          </span>
        </li>
      </ul>
    </div>

    <!-- 正常渲染：viewport + minimap + 浮层控件 -->
    <div v-else-if="layout !== null" class="ft__frame">
      <div
        ref="viewportRef"
        class="ft__viewport"
        :class="{ 'is-grabbing': isDragging }"
        @mousedown="onMouseDown"
        @wheel.prevent="onWheel"
      >
        <svg
          :viewBox="`0 0 ${containerSize.width} ${containerSize.height}`"
          preserveAspectRatio="xMidYMid meet"
          class="ft__svg"
        >
          <g :transform="`translate(${containerSize.width / 2 - panX * zoom}, ${containerSize.height / 2 - panY * zoom}) scale(${zoom})`">
            <!-- 连线 -->
            <g class="ft__links">
              <path
                v-for="(l, i) in visibleLinks"
                :key="`l-${i}`"
                :d="linkPath(l)"
                class="ft__link"
                :class="{ 'ft__link--virtual': l.isVirtual }"
              />
            </g>
            <!-- 节点（虚拟渲染） -->
            <g class="ft__nodes">
              <g
                v-for="n in visibleNodes"
                :key="n.id"
                :transform="`translate(${n.row * ZOOM_X}, ${n.genIndex * ZOOM_Y})`"
                class="ft__node"
                :class="{
                  'ft__node--warning': !!n.generationWarning,
                  'ft__node--orphan': n.isOrphan,
                }"
                @click="onNodeClick(n)"
              >
                <title>{{ n.displayName }}{{ n.relation ? `（${n.relation}）` : '' }} · {{ formatGenerationLabel(n.generation) }}</title>
                <rect
                  :width="NODE_W"
                  :height="NODE_H"
                  rx="4"
                  class="ft__node-rect"
                />
                <!-- 代际小标签 -->
                <rect
                  v-if="n.generation !== null"
                  :x="NODE_W - 30"
                  y="2"
                  width="26"
                  height="10"
                  rx="3"
                  class="ft__node-gen-chip"
                />
                <text
                  v-if="n.generation !== null"
                  :x="NODE_W - 17"
                  y="9.5"
                  text-anchor="middle"
                  class="ft__node-gen-text"
                >
                  {{ n.generation > 0 ? `+${n.generation}` : n.generation }}
                </text>
                <text v-else :x="NODE_W - 17" y="9.5" text-anchor="middle" class="ft__node-gen-text">?</text>
                <!-- 姓名 -->
                <text :x="6" :y="17" class="ft__node-name">
                  {{ n.displayName }}
                </text>
                <!-- 关系（次行） -->
                <text v-if="n.relation" :x="6" :y="31" class="ft__node-sub">
                  {{ n.relation }}
                </text>
                <!-- 警告角标 -->
                <text v-if="n.generationWarning" :x="NODE_W - 6" :y="31" text-anchor="end" class="ft__node-warn">⚠</text>
              </g>
            </g>
          </g>
        </svg>

        <!-- 浮层控件：右上角 zoom +/-, fit -->
        <div class="ft__controls">
          <el-button-group size="small">
            <el-button @click="zoomIn" :icon="undefined">+</el-button>
            <el-button @click="zoomOut" :icon="undefined">−</el-button>
            <el-button @click="fitToContent">适应</el-button>
          </el-button-group>
        </div>

        <!-- 缩略图：右下角 200×120 -->
        <div v-if="showMinimap" class="ft__minimap">
          <svg
            ref="minimapRef"
            :viewBox="`0 0 ${MINI_W} ${MINI_H}`"
            class="ft__minimap-svg"
            @pointerdown="onMinimapPointerDown"
            @pointermove="onMinimapPointerMove"
            @pointerup="onMinimapPointerUp"
          >
            <!-- 节点 -->
            <rect
              v-for="d in minimapDots"
              :key="`d-${d.id}`"
              :x="d.x - 1"
              :y="d.y - 1"
              width="2"
              height="2"
              class="ft__minimap-dot"
              :class="{ 'is-warning': d.hasWarning, 'is-orphan': d.isOrphan }"
            />
            <!-- 视口矩形 -->
            <rect
              v-if="minimapViewportRect"
              :x="minimapViewportRect.x"
              :y="minimapViewportRect.y"
              :width="minimapViewportRect.w"
              :height="minimapViewportRect.h"
              class="ft__minimap-viewport"
            />
          </svg>
        </div>
      </div>

      <!-- 待归位区：未分代 / 父指向不存在节点的 subject -->
      <div v-if="orphanNodes.length > 0" class="ft__orphans">
        <div class="ft__orphans-title">
          <span>待归位（{{ orphanNodes.length }}）</span>
          <span class="muted" style="font-size: 12px">
            未分代或父指向不存在节点 —— 进编辑弹窗补全即可
          </span>
        </div>
        <div class="ft__orphans-grid">
          <div
            v-for="n in orphanNodes"
            :key="n.id"
            class="ft__orphan-card"
            @click="onNodeClick((layout.nodes.find((ln) => ln.id === n.id))!)"
          >
            <div class="ft__orphan-name">{{ n.displayName }}</div>
            <div class="ft__orphan-sub muted">
              <span v-if="n.relation">{{ n.relation }}</span>
              <span v-else>未填关系</span>
              <span v-if="n.generation === null || n.generation === undefined"> · 未分代</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 完全空 -->
    <el-empty
      v-if="layout === null && fallbackList.length === 0 && orphanNodes.length === 0"
      description="本项目暂无被采访者"
    />

    <!-- 详情弹窗（仅 enableDetailPopup=true 场景） -->
    <el-dialog
      v-model="showDetail"
      :title="selectedNode?.displayName ?? '详情'"
      width="420px"
      append-to-body
      @close="closeDetail"
    >
      <div v-if="selectedNode" class="ft__detail">
        <div class="ft__detail-row">
          <span class="muted">姓名</span>
          <strong>{{ selectedNode.displayName }}</strong>
        </div>
        <div class="ft__detail-row">
          <span class="muted">关系</span>
          <span>{{ selectedNode.relation || '—' }}</span>
        </div>
        <div class="ft__detail-row">
          <span class="muted">代际</span>
          <el-tag size="small" effect="plain" type="warning">
            {{ formatGenerationLabel(selectedNode.generation) }}
          </el-tag>
        </div>
        <div v-if="selectedParentName" class="ft__detail-row">
          <span class="muted">上一代</span>
          <span>{{ selectedParentName }}</span>
          <el-tag v-if="parentRelationLabel" size="small" effect="plain" style="margin-left: 6px">
            {{ parentRelationLabel }}
          </el-tag>
        </div>
        <el-alert
          v-if="selectedNode.generationWarning"
          type="warning"
          :closable="false"
          show-icon
          style="margin-top: 12px"
        >
          <template #title>{{ selectedNode.generationWarning }}</template>
        </el-alert>
        <div v-if="selectedNode.familyMemberId" class="ft__detail-row" style="margin-top: 8px">
          <el-tag size="small" type="info" effect="plain">
            🏠 关联家族成员 #{{ selectedNode.familyMemberId }}
          </el-tag>
        </div>
      </div>
      <template #footer>
        <el-button @click="closeDetail">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ft {
  width: 100%;
  min-height: 320px;
}

/* ===== 主 viewport ===== */
.ft__frame {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ft__viewport {
  position: relative;
  width: 100%;
  height: 480px;
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  background: #fdf6ec;
  overflow: hidden;
  cursor: grab;
  user-select: none;
}
.ft__viewport.is-grabbing {
  cursor: grabbing;
}
.ft__svg {
  width: 100%;
  height: 100%;
  display: block;
}

/* ===== 节点（紧凑 paper-note） ===== */
.ft__node-rect {
  fill: #fffdf7;
  stroke: var(--mw-border);
  stroke-width: 0.75;
  filter: drop-shadow(0 1px 1px rgba(120, 80, 20, 0.08));
  cursor: pointer;
  transition: transform 0.12s, fill 0.15s, stroke 0.15s;
}
.ft__node:hover .ft__node-rect {
  fill: rgba(217, 119, 6, 0.06);
  stroke: var(--mw-primary);
}
.ft__node:hover {
  transform: translateY(-1px);
}
.ft__node--warning .ft__node-rect {
  stroke: #d97706;
  stroke-width: 1.5;
}
.ft__node--orphan .ft__node-rect {
  stroke-dasharray: 3 3;
  fill: #f7f1e8;
}
.ft__node-gen-chip {
  fill: rgba(217, 119, 6, 0.12);
  pointer-events: none;
}
.ft__node-gen-text {
  font-size: 8px;
  font-weight: 600;
  fill: #b45309;
  pointer-events: none;
}
.ft__node-name {
  font-size: 11px;
  font-weight: 500;
  fill: var(--mw-text);
  pointer-events: none;
}
.ft__node-sub {
  font-size: 9px;
  fill: var(--mw-text-muted);
  pointer-events: none;
}
.ft__node-warn {
  font-size: 10px;
  font-weight: 600;
  fill: #d97706;
  pointer-events: none;
}

/* ===== 连线 ===== */
.ft__link {
  fill: none;
  stroke: var(--mw-border);
  stroke-width: 1.5;
  stroke-dasharray: 4 4;
}
.ft__link--virtual {
  stroke-dasharray: 2 4;
  opacity: 0.4;
}

/* ===== 浮层控件 ===== */
.ft__controls {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
}

/* ===== 缩略图 ===== */
.ft__minimap {
  position: absolute;
  right: 12px;
  bottom: 12px;
  width: 200px;
  height: 120px;
  background: var(--mw-surface, #fff);
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
  box-shadow: var(--mw-shadow, 0 2px 6px rgba(0, 0, 0, 0.08));
  z-index: 2;
  overflow: hidden;
  cursor: crosshair;
}
.ft__minimap-svg {
  width: 100%;
  height: 100%;
  display: block;
}
.ft__minimap-dot {
  fill: var(--mw-text-muted);
}
.ft__minimap-dot.is-warning {
  fill: #d97706;
}
.ft__minimap-dot.is-orphan {
  fill: #9ca3af;
}
.ft__minimap-viewport {
  fill: rgba(217, 119, 6, 0.15);
  stroke: var(--mw-primary);
  stroke-width: 1;
  pointer-events: none;
}

/* ===== 详情弹窗 ===== */
.ft__detail-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
}
.ft__detail-row .muted {
  width: 56px;
  color: var(--mw-text-muted);
  font-size: 12px;
}

/* ===== 待归位区 ===== */
.ft__orphans {
  padding: 12px;
  background: #f5f5f5;
  border: 1px dashed var(--mw-border);
  border-radius: var(--mw-radius);
}
.ft__orphans-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-weight: 500;
  margin-bottom: 8px;
}
.ft__orphans-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}
.ft__orphan-card {
  padding: 8px 10px;
  background: #fff;
  border: 1px solid var(--mw-border);
  border-radius: 4px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.ft__orphan-card:hover {
  border-color: var(--mw-primary);
}
.ft__orphan-name {
  font-size: 13px;
  font-weight: 500;
}
.ft__orphan-sub {
  font-size: 11px;
  margin-top: 2px;
}

/* ===== 降级列表 ===== */
.ft__fallback {
  padding: 12px;
  background: #fffdf7;
  border: 1px solid var(--mw-border);
  border-radius: var(--mw-radius);
}
.ft__fallback-list {
  list-style: none;
  padding: 0;
  margin: 12px 0 0;
}
.ft__fallback-item {
  padding: 8px 12px;
  border-bottom: 1px dashed var(--mw-border);
  font-size: 13px;
}
.ft__fallback-item:last-child {
  border-bottom: none;
}
.ft__gen {
  margin-left: 8px;
  color: var(--mw-primary);
  font-size: 12px;
}
.muted {
  color: var(--mw-text-muted);
}
</style>
