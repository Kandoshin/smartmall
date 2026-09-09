export type ApiResult<T> = {
  code: number
  message: string
  data: T
}

export type User = {
  id: number
  username: string
  email: string | null
}

export type LoginRequest = {
  username: string
  password: string
}

export type LoginResponse = {
  accessToken: string
  expiresIn: number
  user: User
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
