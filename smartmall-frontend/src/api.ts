import type {
  ApiResult,
  ChatStreamEvent,
  LoginRequest,
  LoginResponse,
  OrderDetail,
  OrderCreateItem,
  OrderSummary,
  PageResult,
  Product,
  User,
} from './types'

type RequestOptions = RequestInit & { timeoutMs?: number }

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(url: string, options?: RequestOptions): Promise<T> {
  options?.signal?.throwIfAborted()
  const { timeoutMs = 10000, ...fetchOptions } = options ?? {}
  let response: Response
  try {
    response = await fetch(url, {
      ...fetchOptions,
      signal: options?.signal
        ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMs)])
        : AbortSignal.timeout(timeoutMs),
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

export function getOrderDetail(accessToken: string, orderId: number, signal?: AbortSignal) {
  return request<OrderDetail>(`/api/orders/${orderId}`, {
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

// Identity travels only in the Bearer token; the body describes the purchase.
export function createOrder(accessToken: string, items: OrderCreateItem[], signal?: AbortSignal) {
  return request<OrderSummary>('/api/orders', {
    method: 'POST',
    credentials: 'omit',
    cache: 'no-store',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify({ items }),
    signal,
  })
}

export function cancelOrder(accessToken: string, orderId: number, signal?: AbortSignal) {
  return request<OrderSummary>(`/api/orders/${orderId}/cancel`, {
    method: 'PATCH',
    credentials: 'omit',
    cache: 'no-store',
    headers: { Authorization: `Bearer ${accessToken}` },
    signal,
  })
}

type ChatStreamHandlers = {
  onDelta: (text: string) => void
  onStatus?: (text: string) => void
}

function waitForNextPaint() {
  if (typeof window === 'undefined') return Promise.resolve()
  return new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
}

export async function streamChatMessage(
  accessToken: string,
  message: string,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
) {
  signal?.throwIfAborted()
  let response: Response
  try {
    response = await fetch('/api/chat', {
      method: 'POST',
      credentials: 'omit',
      cache: 'no-store',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify({ message }),
      signal,
    })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new ApiError(0, '无法连接 AI 服务，请确认后端已启动后重试')
  }

  if (!response.ok) {
    let payload: ApiResult<unknown> | null = null
    try {
      payload = await response.json() as ApiResult<unknown>
    } catch {
      // A proxy may return an empty or HTML error page.
    }
    const fallback = response.status === 401
      ? '登录已失效，请重新登录'
      : `AI 服务响应异常（HTTP ${response.status}）`
    throw new ApiError(response.status, payload?.message || fallback)
  }

  if (!response.body) {
    throw new ApiError(0, '浏览器无法读取 AI 流式响应')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false

  const processFrame = async (frame: string) => {
    if (!frame.trim() || frame.trimStart().startsWith(':')) return
    let eventName = 'message'
    const dataLines: string[] = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    if (!dataLines.length) return

    let payload: ApiResult<ChatStreamEvent>
    try {
      payload = JSON.parse(dataLines.join('\n')) as ApiResult<ChatStreamEvent>
    } catch {
      throw new ApiError(0, 'AI 流式响应格式异常')
    }

    if (eventName === 'error' || payload.code >= 400) {
      throw new ApiError(payload.code || 503, payload.message || 'AI 服务暂时不可用')
    }
    if (eventName === 'done') {
      completed = true
      return
    }
    if ((eventName === 'delta' || eventName === 'status')
      && (typeof payload.data?.text !== 'string' || !payload.data.text)) {
      throw new ApiError(0, 'AI 流式响应缺少文本内容')
    }
    if (eventName === 'delta') {
      handlers.onDelta(payload.data.text)
      await waitForNextPaint()
    }
    if (eventName === 'status') handlers.onStatus?.(payload.data.text)
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      for (const frame of frames) await processFrame(frame)
    }
    buffer += decoder.decode()
    if (buffer.trim()) await processFrame(buffer)
  } finally {
    reader.releaseLock()
  }

  signal?.throwIfAborted()
  if (!completed) {
    throw new ApiError(0, 'AI 流式响应意外中断，请重试')
  }
}
