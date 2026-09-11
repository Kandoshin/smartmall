import type {
  ApiResult,
  LoginRequest,
  LoginResponse,
  OrderCreateItem,
  OrderSummary,
  PageResult,
  Product,
  User,
} from './types'

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  options?.signal?.throwIfAborted()
  let response: Response
  try {
    response = await fetch(url, {
      ...options,
      signal: options?.signal
        ? AbortSignal.any([options.signal, AbortSignal.timeout(10000)])
        : AbortSignal.timeout(10000),
    })
  } catch (error) {
    if (options?.signal?.aborted) throw error
    throw new ApiError(0, '无法连接服务或请求超时，请确认后端已启动后重试')
  }

  const fallbackMessage = response.status === 401
    ? '登录已失效，请重新登录'
    : `服务响应异常（HTTP ${response.status}），请确认后端已启动后重试`
  let payload: ApiResult<T>
  try {
    payload = (await response.json()) as ApiResult<T>
  } catch (error) {
    if (options?.signal?.aborted) throw error
    throw new ApiError(response.status, fallbackMessage)
  }
  options?.signal?.throwIfAborted()

  if (!payload || typeof payload.code !== 'number') {
    throw new ApiError(response.status, fallbackMessage)
  }

  if (!response.ok || payload.code >= 400) {
    throw new ApiError(response.ok ? payload.code : response.status, payload.message || fallbackMessage)
  }

  return payload.data
}

async function getAuthCsrf(signal?: AbortSignal) {
  // Read a masked CSRF token; the matching HttpOnly cookie is handled by the browser.
  // Fetch for each auth write instead of persisting or sharing credentials in storage.
  const csrf = await request<{ headerName: string; token: string }>('/api/auth/csrf', {
    credentials: 'same-origin', cache: 'no-store', signal,
  })
  if (!csrf || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) {
    throw new ApiError(0, '无法初始化登录校验，请稍后重试')
  }
  signal?.throwIfAborted()
  return csrf
}

export async function login(credentials: LoginRequest, signal?: AbortSignal) {
  const csrf = await getAuthCsrf(signal)
  return request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(credentials),
    signal,
  })
}

// The browser sends the HttpOnly refresh cookie; JavaScript never reads its value.
// Called at page startup to restore an in-memory Access Token, never to persist it.
export async function refreshSession(signal?: AbortSignal) {
  const csrf = await getAuthCsrf(signal)
  return request<LoginResponse>('/api/auth/refresh', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { [csrf.headerName]: csrf.token },
    signal,
  })
}

// Clear the browser's refresh cookie even when no usable Access Token remains.
export async function logoutSession(signal?: AbortSignal) {
  const csrf = await getAuthCsrf(signal)
  return request<null>('/api/auth/logout', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { [csrf.headerName]: csrf.token },
    signal,
  })
}

// Only explicitly protected requests receive a token, never every request globally.
export function getCurrentUser(accessToken: string, signal?: AbortSignal) {
  return request<User>('/api/auth/me', {
    headers: { Authorization: `Bearer ${accessToken}` },
    signal,
  })
}

export function getMyOrders(accessToken: string, signal?: AbortSignal) {
  return request<OrderSummary[]>('/api/orders/me', {
    headers: { Authorization: `Bearer ${accessToken}` },
    credentials: 'omit',
    cache: 'no-store',
    signal,
  })
}

export function getProducts(params: {
  name: string
  status: string
  page: number
  size: number
}) {
  const query = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
  })

  if (params.name.trim()) {
    query.set('name', params.name.trim())
  }
  if (params.status !== '') {
    query.set('status', params.status)
  }

  return request<PageResult<Product>>(`/api/products?${query.toString()}`)
}

export function createOrder(userId: number, items: OrderCreateItem[]) {
  return request<OrderSummary>('/api/orders', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ userId, items }),
  })
}

export function cancelOrder(orderId: number) {
  return request<OrderSummary>(`/api/orders/${orderId}/cancel`, {
    method: 'PATCH',
  })
}
