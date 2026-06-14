/**
 * 认证状态（Pinia）。
 * - accessToken / refreshToken 持久化到 localStorage
 * - 提供 login / register / logout / fetchMe 动作
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as authApi from '@/api/auth'
import type { UserVO } from '@/api/auth'

const ACCESS_KEY = 'mw_access_token'
const REFRESH_KEY = 'mw_refresh_token'
const USER_KEY = 'mw_user'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(localStorage.getItem(ACCESS_KEY))
  const refreshToken = ref<string | null>(localStorage.getItem(REFRESH_KEY))

  // 从 localStorage 恢复用户基本信息
  const initialUser = localStorage.getItem(USER_KEY)
  const user = ref<UserVO | null>(initialUser ? JSON.parse(initialUser) : null)

  const isLoggedIn = computed(() => !!accessToken.value)

  function setTokens(access: string, refresh: string) {
    accessToken.value = access
    refreshToken.value = refresh
    localStorage.setItem(ACCESS_KEY, access)
    localStorage.setItem(REFRESH_KEY, refresh)
  }

  function setUser(u: UserVO | null) {
    user.value = u
    if (u) localStorage.setItem(USER_KEY, JSON.stringify(u))
    else localStorage.removeItem(USER_KEY)
  }

  function clear() {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  }

  async function login(account: string, password: string) {
    const { data } = await authApi.login({ account, password })
    if (!data || data.code !== 0 || !data.data) {
      throw new Error(data?.message || '登录失败')
    }
    setTokens(data.data.accessToken, data.data.refreshToken)
    setUser(data.data.user)
    return data.data
  }

  async function register(account: string, password: string, displayName: string) {
    const { data } = await authApi.register({ account, password, displayName })
    if (!data || data.code !== 0 || !data.data) {
      throw new Error(data?.message || '注册失败')
    }
    setTokens(data.data.accessToken, data.data.refreshToken)
    setUser(data.data.user)
    return data.data
  }

  async function fetchMe() {
    if (!accessToken.value) return null
    try {
      const { data } = await authApi.me()
      if (data && data.code === 0 && data.data) {
        setUser(data.data)
        return data.data
      }
    } catch (e) {
      // 401 由 axios 拦截器统一处理
      console.warn('[auth] fetchMe failed', e)
    }
    return null
  }

  async function logout() {
    try {
      if (accessToken.value) await authApi.logout()
    } catch (e) {
      // 忽略后端报错，本地清空即可
    } finally {
      clear()
    }
  }

  return {
    accessToken,
    refreshToken,
    user,
    isLoggedIn,
    login,
    register,
    logout,
    fetchMe,
    clear,
  }
})
