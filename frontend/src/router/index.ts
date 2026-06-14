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
  {
    path: '/health-check',
    name: 'health-check',
    component: () => import('@/views/HealthCheck.vue'),
    meta: { public: true, title: '连通性自检' },
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
      {
        path: 'projects/:id',
        name: 'project-detail',
        component: () => import('@/views/project/ProjectDetail.vue'),
        meta: { title: '项目详情' },
      },
      {
        path: 'interview/:id',
        name: 'interview-room',
        component: () => import('@/views/interview/InterviewRoom.vue'),
        meta: { title: '采访房间' },
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

  if (isPublic) {
    // 已登录用户访问 /login 或 /register 时，直接送到项目列表
    if (auth.isLoggedIn && (to.name === 'login' || to.name === 'register')) {
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
