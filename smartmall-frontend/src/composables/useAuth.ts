import { onUnmounted, ref } from 'vue'
import { ApiError, getCurrentUser, login } from '../api'
import type { User } from '../types'

export function useAuth() {
  const username = ref('')
  const password = ref('')
  const currentUser = ref<User | null>(null)
  const busy = ref(false)
  const errorMessage = ref('')
  const statusMessage = ref('')

  // Only page memory: no localStorage, sessionStorage, cookies, or URL parameters.
  let accessToken: string | null = null
  let expiresAt = 0
  let expiryTimer: ReturnType<typeof setTimeout> | undefined
  let pendingRequest: AbortController | null = null
  let requestVersion = 0

  function clearSession() {
    accessToken = null
    currentUser.value = null
    expiresAt = 0
    clearTimeout(expiryTimer)
  }

  function cancelPendingRequest() {
    requestVersion += 1
    pendingRequest?.abort()
    pendingRequest = null
    busy.value = false
  }

  function expireSession() {
    cancelPendingRequest()
    clearSession()
    password.value = ''
    statusMessage.value = ''
    errorMessage.value = '登录已过期，请重新登录'
  }

  async function handleLogin() {
    if (busy.value) return
    if (!username.value.trim() || !password.value) {
      errorMessage.value = '请输入用户名和密码'
      return
    }

    const version = ++requestVersion
    pendingRequest = new AbortController()
    const signal = pendingRequest.signal
    busy.value = true
    errorMessage.value = ''
    statusMessage.value = '正在登录并验证当前身份…'
    clearSession()

    try {
      const startedAt = Date.now()
      const result = await login({ username: username.value, password: password.value }, signal)
      password.value = ''
      if (version !== requestVersion) return
      if (!result?.accessToken || !Number.isFinite(result.expiresIn) || result.expiresIn <= 0) {
        throw new Error('登录响应缺少有效凭证，请稍后重试')
      }

      accessToken = result.accessToken
      expiresAt = startedAt + result.expiresIn * 1000
      // Show the user returned by the protected endpoint, not just the login response.
      const user = await getCurrentUser(accessToken, signal)
      if (version !== requestVersion) return
      if (Date.now() >= expiresAt) {
        expireSession()
        return
      }
      currentUser.value = user
      statusMessage.value = '登录成功，当前身份已由后端验证'
      expiryTimer = setTimeout(expireSession, expiresAt - Date.now())
    } catch (error) {
      if (version !== requestVersion) return
      clearSession()
      statusMessage.value = ''
      errorMessage.value = error instanceof Error ? error.message : '登录失败，请重试'
    } finally {
      if (version === requestVersion) {
        busy.value = false
        password.value = ''
        pendingRequest = null
      }
    }
  }

  async function refreshCurrentUser() {
    if (busy.value || !accessToken) return
    if (Date.now() >= expiresAt) {
      expireSession()
      return
    }

    const version = ++requestVersion
    pendingRequest = new AbortController()
    busy.value = true
    errorMessage.value = ''
    statusMessage.value = '正在向后端确认身份…'
    try {
      const user = await getCurrentUser(accessToken, pendingRequest.signal)
      if (version !== requestVersion) return
      currentUser.value = user
      statusMessage.value = '当前身份验证成功（不会延长登录有效期）'
    } catch (error) {
      if (version !== requestVersion) return
      if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
        clearSession()
      }
      statusMessage.value = ''
      errorMessage.value = error instanceof Error ? error.message : '身份确认失败，请重试'
    } finally {
      if (version === requestVersion) {
        busy.value = false
        pendingRequest = null
      }
    }
  }

  function logout() {
    cancelPendingRequest()
    clearSession()
    password.value = ''
    errorMessage.value = ''
    statusMessage.value = '已清除本页登录状态'
  }

  onUnmounted(() => {
    cancelPendingRequest()
    clearSession()
  })

  return { username, password, currentUser, busy, errorMessage, statusMessage,
    handleLogin, refreshCurrentUser, logout }
}
