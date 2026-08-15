import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  // 公开页
  {
    path: '/',
    redirect: '/projects',
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/Login.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/Register.vue'),
    meta: { public: true, title: '注册' },
  },
  // M10+: 强制改密页（必须登录但不需要 Layout）
  {
    path: '/change-password',
    name: 'change-password',
    component: () => import('@/views/auth/ChangePassword.vue'),
    meta: { public: true, layout: 'blank', title: '修改密码' },
  },
  {
    path: '/health-check',
    name: 'health-check',
    component: () => import('@/views/HealthCheck.vue'),
    meta: { public: true, title: '连通性自检' },
  },

  // M5-B.3: 隐私政策 / 用户协议（公开）
  {
    path: '/privacy',
    name: 'privacy',
    component: () => import('@/views/Privacy.vue'),
    meta: { public: true, layout: 'blank', title: '隐私政策' },
  },

  // 鉴权页（带 Layout）
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    children: [
      {
        path: 'projects',
        name: 'projects',
        component: () => import('@/views/project/ProjectList.vue'),
        meta: { title: '我的项目' },
      },
      {
        path: 'projects/new',
        name: 'project-create',
        component: () => import('@/views/project/ProjectCreate.vue'),
        meta: { title: '新建项目' },
      },
      // 项目级外壳：统一页头 + 二级导航（概览 / 时间线 / 成稿 / 分享）。
      // 必须排在 'projects/new' 之后，否则 new 会被 :id 匹配掉。
      {
        path: 'projects/:id',
        component: () => import('@/views/project/ProjectLayout.vue'),
        // ProjectLayout 自带「← 我的项目 + 项目名」页头，顶部面包屑不再重复
        meta: { hideCrumb: true },
        children: [
          {
            path: '',
            name: 'project-detail',
            component: () => import('@/views/project/ProjectDetail.vue'),
            meta: { title: '项目概览' },
          },
          {
            path: 'timeline',
            name: 'project-timeline',
            component: () => import('@/views/timeline/Timeline.vue'),
            meta: { title: '时间线' },
          },
          // M4: 成稿
          {
            path: 'drafts',
            name: 'project-drafts',
            component: () => import('@/views/draft/DraftList.vue'),
            meta: { title: '成稿' },
          },
          // M5-A: 分享管理
          {
            path: 'shares',
            name: 'project-shares',
            component: () => import('@/views/share/ShareManage.vue'),
            meta: { title: '分享管理' },
          },
        ],
      },
      {
        path: 'interview/:id',
        name: 'interview-room',
        component: () => import('@/views/interview/InterviewRoom.vue'),
        meta: { title: '采访房间' },
      },
      {
        path: 'interview/:id/summary',
        name: 'interview-summary',
        component: () => import('@/views/interview/InterviewSummary.vue'),
        meta: { title: '采访摘要' },
      },
      {
        path: 'timeline/event/:eventId',
        name: 'timeline-event',
        component: () => import('@/views/timeline/MomentDetail.vue'),
        meta: { title: '事件详情' },
      },
      // M4: 成稿编辑 / 阅读（不属于项目二级导航，独立全屏页）
      {
        path: 'drafts/:did/edit',
        name: 'draft-edit',
        component: () => import('@/views/draft/DraftEditor.vue'),
        meta: { title: '成稿编辑' },
      },
      {
        path: 'drafts/:did/read',
        name: 'draft-read',
        component: () => import('@/views/draft/DraftReader.vue'),
        meta: { title: '成稿阅读' },
      },
      // M5-A.2: 通知中心
      {
        path: 'notifications',
        name: 'notifications',
        component: () => import('@/views/notification/NotificationList.vue'),
        meta: { title: '通知中心' },
      },
      // M5-B: 合规中心
      {
        path: 'compliance',
        name: 'compliance',
        component: () => import('@/views/compliance/ComplianceCenter.vue'),
        meta: { title: '合规中心' },
      },
      // M10+ Family: 家族
      {
        path: 'families',
        name: 'families',
        component: () => import('@/views/family/FamilyList.vue'),
        meta: { title: '家族' },
      },
      {
        path: 'families/new',
        name: 'family-create',
        component: () => import('@/views/family/FamilyCreate.vue'),
        meta: { title: '创建家族' },
      },
      {
        path: 'families/:id',
        name: 'family-detail',
        component: () => import('@/views/family/FamilyDetail.vue'),
        meta: { hideCrumb: true, title: '家族详情' },
      },
    ],
  },

  // 公开授权页（无 Layout，无 JWT）
  {
    path: '/authz/:token',
    name: 'consent',
    component: () => import('@/views/authorization/Consent.vue'),
    meta: { public: true, layout: 'blank', title: '知情同意' },
  },

  // 公开分享阅读页（无 Layout，无 JWT）
  {
    path: '/share/:token',
    name: 'public-share',
    component: () => import('@/views/share/PublicShareView.vue'),
    meta: { public: true, layout: 'blank', title: '分享阅读' },
  },

  // 兜底
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFound.vue'),
    meta: { public: true, title: '404' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫
router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  const isPublic = to.meta?.public === true

  if (to.meta?.title) {
    document.title = `${to.meta.title} · Moment Weaver`
  }

  // M10+ Family：强制改密拦截
  // mustChangePassword=true 时，所有非 /change-password / /logout 路由都强制跳改密
  if (auth.isLoggedIn && auth.user?.mustChangePassword) {
    if (to.name !== 'change-password' && to.name !== 'login' && to.name !== 'logout') {
      return next({ name: 'change-password' })
    }
  }

  if (isPublic) {
    // 已登录用户访问 /login 或 /register 时，直接送到项目列表
    if (auth.isLoggedIn && (to.name === 'login' || to.name === 'register')) {
      // 但如果是强制改密场景，仍走改密
      if (auth.user?.mustChangePassword) return next({ name: 'change-password' })
      return next({ name: 'projects' })
    }
    return next()
  }

  // 私有页面：未登录跳登录，附 redirect
  if (!auth.isLoggedIn) {
    return next({ name: 'login', query: { redirect: to.fullPath } })
  }

  next()
})

export default router
