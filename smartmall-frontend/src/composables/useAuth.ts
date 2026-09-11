import { onMounted, onUnmounted, ref } from 'vue'
import { ApiError, getCurrentUser, getMyOrders, login, logoutSession, refreshSession } from '../api'
import type { LoginResponse, User } from '../types'

export function useAuth() {
  const username = ref('')
  const password = ref('')
  const currentUser = ref<User | null>(null)
  const busy = ref(false)
  const errorMessage = ref('')
  const statusMessage = ref('')
  const logoutNeedsRetry = ref(false)
  // Unknown identity is different from logged out: do not flash the login form at startup.
  const restoring = ref(true)
  const restoreNeedsRetry = ref(false)
  let loggingOut = false

  // Only page memory: no localStorage, sessionStorage, cookies, or URL parameters.
  let accessToken: string | null = null
  let expiresAt = 0
  let expiryTimer: ReturnType<typeof setTimeout> | undefined
  let pendingRequest: AbortController | null = null
  let requestVersion = 0
  let sessionRequests = new AbortController()

  function clearSession() {
    sessionRequests.abort()
    sessionRequests = new AbortController()
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

  // Commerce receives this operation, not a second auth instance or a persisted token.
  async function fetchMyOrders(signal?: AbortSignal) {
    if (!accessToken || !currentUser.value || Date.now() >= expiresAt) {
      if (currentUser.value) expireSession()
      throw new ApiError(401, '登录已失效，请重新登录')
    }

    const version = requestVersion
    const requestSignal = signal
      ? AbortSignal.any([signal, sessionRequests.signal])
      : sessionRequests.signal
    try {
      const orders = await getMyOrders(accessToken, requestSignal)
      // Logout/account changes invalidate even responses already received by the browser.
      requestSignal.throwIfAborted()
      if (!Array.isArray(orders)) throw new ApiError(0, '订单响应格式异常，请稍后重试')
      return orders
    } catch (error) {
      if (!requestSignal.aborted && version === requestVersion
        && error instanceof ApiError && error.status === 401) {
        expireSession()
      }
      throw error
    }
  }

  // Both password login and Cookie restoration receive the same LoginResponse contract.
  async function acceptSession(result: LoginResponse, startedAt: number, signal: AbortSignal, version: number) {
    if (version !== requestVersion) return false
    if (typeof result?.accessToken !== 'string' || !result.accessToken.trim()
      || !Number.isFinite(result.expiresIn) || result.expiresIn <= 0
      || result.expiresIn * 1000 > 2_147_483_647) {
      throw new Error('登录响应缺少有效凭证，请稍后重试')
    }
    accessToken = result.accessToken
    expiresAt = startedAt + result.expiresIn * 1000
    const user = await getCurrentUser(accessToken, signal)
    if (version !== requestVersion) return false
    if (Date.now() >= expiresAt) throw new Error('登录已过期，请重新登录')
    if (!user || !Number.isSafeInteger(user.id) || user.id <= 0
      || typeof user.username !== 'string' || !user.username) {
      throw new Error('当前用户信息无效，请稍后重试')
    }
    // Display only the identity confirmed by the protected endpoint.
    currentUser.value = user
    logoutNeedsRetry.value = false
    restoreNeedsRetry.value = false
    expiryTimer = setTimeout(expireSession, expiresAt - Date.now())
    return true
  }

  async function restoreSession() {
    if (busy.value || currentUser.value || loggingOut || logoutNeedsRetry.value) return
    const version = ++requestVersion
    pendingRequest = new AbortController()
    const signal = pendingRequest.signal
    restoring.value = true
    busy.value = true
    restoreNeedsRetry.value = false
    errorMessage.value = ''
    statusMessage.value = ''
    clearSession()

    try {
      const startedAt = Date.now()
      const result = await refreshSession(signal)
      if (await acceptSession(result, startedAt, signal, version)) {
        statusMessage.value = '已恢复登录，当前身份已由后端验证'
      }
    } catch (error) {
      if (version !== requestVersion) return
      clearSession()
      // First visit and expired/rejected credentials lead to normal login, not a retry loop.
      if (!(error instanceof ApiError && error.status === 401)) {
        restoreNeedsRetry.value = true
        errorMessage.value = '暂时无法恢复登录，可重试或使用账号登录。'
          + (error instanceof Error ? error.message : '')
      }
    } finally {
      if (version === requestVersion) {
        restoring.value = false
        busy.value = false
        pendingRequest = null
      }
    }
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
      if (await acceptSession(result, startedAt, signal, version)) {
        statusMessage.value = '登录成功，当前身份已由后端验证'
      }
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

  async function logout() {
    if (loggingOut) return
    loggingOut = true
    cancelPendingRequest()
    restoring.value = false
    restoreNeedsRetry.value = false
    clearSession()
    password.value = ''
    errorMessage.value = ''
    statusMessage.value = '正在退出登录…'
    const version = requestVersion
    pendingRequest = new AbortController()
    busy.value = true

    try {
      await logoutSession(pendingRequest.signal)
      if (version !== requestVersion) return
      logoutNeedsRetry.value = false
      statusMessage.value = '已退出登录'
    } catch (error) {
      if (version !== requestVersion) return
      logoutNeedsRetry.value = true
      statusMessage.value = ''
      errorMessage.value = '已清除本页登录状态，但未能确认清除浏览器登录凭证，请重试退出。'
        + (error instanceof Error ? error.message : '')
    } finally {
      loggingOut = false
      if (version === requestVersion) {
        busy.value = false
        pendingRequest = null
      }
    }
  }

  onMounted(() => { void restoreSession() })

  onUnmounted(() => {
    cancelPendingRequest()
    clearSession()
  })

  return { username, password, currentUser, busy, errorMessage, statusMessage, logoutNeedsRetry,
    restoring, restoreNeedsRetry, restoreSession, handleLogin, logout, fetchMyOrders }
}
