import type {
  ApiResult,
  OrderCreateItem,
  OrderSummary,
  PageResult,
  Product,
} from './types'

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options)
  const payload = (await response.json()) as ApiResult<T>

  if (!response.ok || payload.code >= 400) {
    throw new Error(payload.message || `请求失败（HTTP ${response.status}）`)
  }

  return payload.data
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
