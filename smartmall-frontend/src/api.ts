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
  } catch {
    throw new ApiError(response.status, fallbackMessage)
  }

  if (!payload || typeof payload.code !== 'number') {
    throw new ApiError(response.status, fallbackMessage)
  }

  if (!response.ok || payload.code >= 400) {
    throw new ApiError(response.ok ? payload.code : response.status, payload.message || fallbackMessage)
  }

  return payload.data
}

export async function login(credentials: LoginRequest, signal?: AbortSignal) {
  // Read a masked CSRF token; the matching HttpOnly cookie is handled by the browser.
  // Fetch on every login attempt instead of persisting or sharing credentials in storage.
  const csrf = await request<{ headerName: string; token: string }>('/api/auth/csrf', {
    credentials: 'same-origin', cache: 'no-store', signal,
  })
  if (!csrf || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) {
    throw new ApiError(0, '无法初始化登录校验，请稍后重试')
  }
  signal?.throwIfAborted()
  return request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(credentials),
    signal,
  })
}

// Explicit token parameter: never attach credentials to unrelated product/order requests.
export function getCurrentUser(accessToken: string, signal?: AbortSignal) {
  return request<User>('/api/auth/me', {
    headers: { Authorization: `Bearer ${accessToken}` },
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

export function getOrdersByUserId(userId: number) {
  return request<OrderSummary[]>(`/api/orders?userId=${userId}`)
}

export function cancelOrder(orderId: number) {
  return request<OrderSummary>(`/api/orders/${orderId}/cancel`, {
    method: 'PATCH',
  })
}
