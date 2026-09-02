export type ApiResult<T> = {
  code: number
  message: string
  data: T
}

export type PageResult<T> = {
  records: T[]
  current: number
  size: number
  total: number
  pages: number
}

export type Product = {
  id: number
  name: string
  description: string
  price: number
  stock: number
  status: number
}

export type CartItem = Product & {
  quantity: number
}

export type OrderSummary = {
  id: number
  totalAmount: number
  status: string
}

export type OrderCreateItem = {
  productId: number
  quantity: number
}
