<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  cancelOrder as cancelOrderRequest,
  createOrder,
  getOrdersByUserId,
  getProducts,
} from './api'
import type { CartItem, OrderSummary, Product } from './types'

type ViewName = 'products' | 'orders'
type Notice = { message: string; tone: 'success' | 'error' }

const currentView = ref<ViewName>('products')
const products = ref<Product[]>([])
const productsLoading = ref(false)
const productsError = ref('')
const nameFilter = ref('')
const statusFilter = ref('1')
const currentPage = ref(1)
const totalPages = ref(1)
const totalProducts = ref(0)

const cart = ref<CartItem[]>([])
const checkoutUserId = ref(1)
const checkoutLoading = ref(false)

const orderQueryUserId = ref(1)
const orders = ref<OrderSummary[]>([])
const ordersLoading = ref(false)
const ordersError = ref('')
const notice = ref<Notice | null>(null)
let noticeTimer: number | undefined

const cartCount = computed(() =>
  cart.value.reduce((sum, item) => sum + item.quantity, 0),
)

const cartTotal = computed(() =>
  cart.value.reduce((sum, item) => sum + item.price * item.quantity, 0),
)

function formatPrice(value: number) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
  }).format(value)
}

function formatOrderStatus(status: string) {
  const labels: Record<string, string> = {
    PENDING_PAYMENT: '待支付',
    PAID: '已支付',
    CANCELLED: '已取消',
    COMPLETED: '已完成',
  }
  return labels[status] ?? status
}

function showNotice(message: string, tone: Notice['tone']) {
  notice.value = { message, tone }
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => {
    notice.value = null
  }, 3200)
}

async function loadProducts(page = 1) {
  productsLoading.value = true
  productsError.value = ''

  try {
    const result = await getProducts({
      name: nameFilter.value,
      status: statusFilter.value,
      page,
      size: 8,
    })
    products.value = result.records
    currentPage.value = result.current
    totalPages.value = Math.max(result.pages, 1)
    totalProducts.value = result.total
  } catch (error) {
    productsError.value =
      error instanceof Error ? error.message : '商品加载失败'
  } finally {
    productsLoading.value = false
  }
}

function resetFilters() {
  nameFilter.value = ''
  statusFilter.value = '1'
  void loadProducts(1)
}

function addToCart(product: Product) {
  const existing = cart.value.find((item) => item.id === product.id)

  if (existing) {
    if (existing.quantity < product.stock) {
      existing.quantity += 1
    } else {
      showNotice(`${product.name} 已达到库存上限`, 'error')
      return
    }
  } else {
    cart.value.push({ ...product, quantity: 1 })
  }

  showNotice(`${product.name} 已加入购物车`, 'success')
}

function changeQuantity(item: CartItem, change: number) {
  const nextQuantity = item.quantity + change
  if (nextQuantity < 1) {
    removeFromCart(item.id)
    return
  }
  item.quantity = Math.min(nextQuantity, item.stock)
}

function removeFromCart(productId: number) {
  cart.value = cart.value.filter((item) => item.id !== productId)
}

async function checkout() {
  if (cart.value.length === 0) {
    showNotice('请先选择商品', 'error')
    return
  }
  if (!Number.isInteger(checkoutUserId.value) || checkoutUserId.value < 1) {
    showNotice('请输入有效的用户 ID', 'error')
    return
  }

  checkoutLoading.value = true
  try {
    const order = await createOrder(
      checkoutUserId.value,
      cart.value.map((item) => ({
        productId: item.id,
        quantity: item.quantity,
      })),
    )
    cart.value = []
    orderQueryUserId.value = checkoutUserId.value
    showNotice(`订单 #${order.id} 创建成功`, 'success')
    currentView.value = 'orders'
    await loadOrders()
  } catch (error) {
    showNotice(
      error instanceof Error ? error.message : '创建订单失败',
      'error',
    )
  } finally {
    checkoutLoading.value = false
  }
}

async function loadOrders() {
  if (!Number.isInteger(orderQueryUserId.value) || orderQueryUserId.value < 1) {
    ordersError.value = '请输入有效的用户 ID'
    return
  }

  ordersLoading.value = true
  ordersError.value = ''
  try {
    orders.value = await getOrdersByUserId(orderQueryUserId.value)
  } catch (error) {
    ordersError.value = error instanceof Error ? error.message : '订单加载失败'
  } finally {
    ordersLoading.value = false
  }
}

async function handleCancelOrder(order: OrderSummary) {
  try {
    const updatedOrder = await cancelOrderRequest(order.id)
    order.status = updatedOrder.status
    showNotice(`订单 #${order.id} 已取消`, 'success')
  } catch (error) {
    showNotice(
      error instanceof Error ? error.message : '取消订单失败',
      'error',
    )
  }
}

function switchView(view: ViewName) {
  currentView.value = view
  if (view === 'orders' && orders.value.length === 0) {
    void loadOrders()
  }
}

onMounted(() => {
  void loadProducts()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <button class="brand" type="button" @click="switchView('products')">
        <span class="brand-mark">S</span>
        <span>
          <strong>SmartMall</strong>
          <small>微服务商城</small>
        </span>
      </button>

      <nav aria-label="主导航">
        <button
          type="button"
          :class="{ active: currentView === 'products' }"
          @click="switchView('products')"
        >
          商品
        </button>
        <button
          type="button"
          :class="{ active: currentView === 'orders' }"
          @click="switchView('orders')"
        >
          我的订单
        </button>
      </nav>

      <div class="cart-chip">购物车 {{ cartCount }}</div>
    </header>

    <main v-if="currentView === 'products'" class="page-grid">
      <section class="catalog">
        <div class="hero-copy">
          <span class="eyebrow">SMART CHOICES · SIMPLE SHOPPING</span>
          <h1>把好商品，放进简单的购物流程里。</h1>
          <p>浏览商品、加入购物车并创建订单，体验 SmartMall 的微服务调用链路。</p>
        </div>

        <form class="filters" @submit.prevent="loadProducts(1)">
          <label>
            <span>商品名称</span>
            <input v-model="nameFilter" placeholder="搜索商品" />
          </label>
          <label>
            <span>商品状态</span>
            <select v-model="statusFilter">
              <option value="1">仅上架</option>
              <option value="0">仅下架</option>
              <option value="">全部</option>
            </select>
          </label>
          <button class="primary" type="submit">查询</button>
          <button class="ghost" type="button" @click="resetFilters">重置</button>
        </form>

        <div class="section-heading">
          <div>
            <span class="eyebrow">PRODUCTS</span>
            <h2>商品列表</h2>
          </div>
          <span>{{ totalProducts }} 件商品</span>
        </div>

        <div v-if="productsLoading" class="state-card">正在加载商品...</div>
        <div v-else-if="productsError" class="state-card error-state">
          <strong>商品加载失败</strong>
          <span>{{ productsError }}</span>
          <button type="button" @click="loadProducts(currentPage)">重新加载</button>
        </div>
        <div v-else-if="products.length === 0" class="state-card">暂无匹配商品</div>

        <div v-else class="product-grid">
          <article v-for="product in products" :key="product.id" class="product-card">
            <div class="product-visual">
              <span>#{{ String(product.id).padStart(2, '0') }}</span>
              <strong>{{ product.name.slice(0, 1) }}</strong>
            </div>
            <div class="product-body">
              <div class="product-meta">
                <span :class="['status-dot', { muted: product.status !== 1 }]">
                  {{ product.status === 1 ? '在售' : '已下架' }}
                </span>
                <span>库存 {{ product.stock }}</span>
              </div>
              <h3>{{ product.name }}</h3>
              <p>{{ product.description || '暂无商品介绍' }}</p>
              <div class="product-action">
                <strong>{{ formatPrice(product.price) }}</strong>
                <button
                  type="button"
                  :disabled="product.status !== 1 || product.stock < 1"
                  @click="addToCart(product)"
                >
                  加入购物车
                </button>
              </div>
            </div>
          </article>
        </div>

        <div class="pagination">
          <button
            type="button"
            :disabled="currentPage <= 1 || productsLoading"
            @click="loadProducts(currentPage - 1)"
          >
            上一页
          </button>
          <span>第 {{ currentPage }} / {{ totalPages }} 页</span>
          <button
            type="button"
            :disabled="currentPage >= totalPages || productsLoading"
            @click="loadProducts(currentPage + 1)"
          >
            下一页
          </button>
        </div>
      </section>

      <aside class="cart-panel">
        <div class="section-heading compact">
          <div>
            <span class="eyebrow">CART</span>
            <h2>购物车</h2>
          </div>
          <span>{{ cartCount }} 件</span>
        </div>

        <div v-if="cart.length === 0" class="empty-cart">
          <span>购物袋还是空的</span>
          <p>从左侧选择一个商品开始。</p>
        </div>

        <div v-else class="cart-items">
          <article v-for="item in cart" :key="item.id" class="cart-item">
            <div>
              <strong>{{ item.name }}</strong>
              <span>{{ formatPrice(item.price) }}</span>
            </div>
            <div class="quantity-control">
              <button type="button" @click="changeQuantity(item, -1)">−</button>
              <span>{{ item.quantity }}</span>
              <button type="button" @click="changeQuantity(item, 1)">+</button>
              <button class="remove" type="button" @click="removeFromCart(item.id)">
                移除
              </button>
            </div>
          </article>
        </div>

        <div class="checkout-box">
          <label>
            <span>下单用户 ID</span>
            <input v-model.number="checkoutUserId" min="1" type="number" />
          </label>
          <div class="cart-total">
            <span>合计</span>
            <strong>{{ formatPrice(cartTotal) }}</strong>
          </div>
          <button
            class="checkout-button"
            type="button"
            :disabled="checkoutLoading || cart.length === 0"
            @click="checkout"
          >
            {{ checkoutLoading ? '正在创建订单...' : '提交订单' }}
          </button>
        </div>
      </aside>
    </main>

    <main v-else class="orders-page">
      <div class="hero-copy orders-hero">
        <span class="eyebrow">ORDER CENTER</span>
        <h1>查看订单状态，管理待支付订单。</h1>
      </div>

      <form class="order-query" @submit.prevent="loadOrders">
        <label>
          <span>用户 ID</span>
          <input v-model.number="orderQueryUserId" min="1" type="number" />
        </label>
        <button class="primary" type="submit">查询订单</button>
      </form>

      <div v-if="ordersLoading" class="state-card">正在加载订单...</div>
      <div v-else-if="ordersError" class="state-card error-state">
        <strong>订单加载失败</strong>
        <span>{{ ordersError }}</span>
      </div>
      <div v-else-if="orders.length === 0" class="state-card">该用户暂无订单</div>

      <div v-else class="order-list">
        <article v-for="order in orders" :key="order.id" class="order-card">
          <div>
            <span>订单编号</span>
            <strong>#{{ order.id }}</strong>
          </div>
          <div>
            <span>订单金额</span>
            <strong>{{ formatPrice(order.totalAmount) }}</strong>
          </div>
          <div>
            <span>当前状态</span>
            <strong class="order-status">{{ formatOrderStatus(order.status) }}</strong>
          </div>
          <button
            type="button"
            :disabled="order.status !== 'PENDING_PAYMENT'"
            @click="handleCancelOrder(order)"
          >
            {{ order.status === 'PENDING_PAYMENT' ? '取消订单' : '不可取消' }}
          </button>
        </article>
      </div>
    </main>

    <Transition name="toast">
      <div v-if="notice" :class="['toast', notice.tone]">
        {{ notice.message }}
      </div>
    </Transition>
  </div>
</template>
