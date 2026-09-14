<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { getProducts } from '../api'
import type { CartItem, OrderCreateItem, OrderSummary, Product } from '../types'

type ViewName = 'products' | 'orders'
type Notice = { message: string; tone: 'success' | 'error' }

const props = withDefaults(defineProps<{
  view: ViewName
  layout?: 'drawer' | 'storefront'
  fetchOrders: (signal?: AbortSignal) => Promise<OrderSummary[]>
  createOrder: (items: OrderCreateItem[], signal?: AbortSignal) => Promise<OrderSummary>
  cancelOrder: (orderId: number, signal?: AbortSignal) => Promise<OrderSummary>
}>(), { layout: 'drawer' })
const emit = defineEmits<{ navigate: [view: ViewName] }>()
const currentView = computed(() => props.view)
const products = ref<Product[]>([])
const productsLoading = ref(false)
const productsError = ref('')
const nameFilter = ref('')
const statusFilter = ref('1')
const currentPage = ref(1)
const totalPages = ref(1)
const totalProducts = ref(0)

const cart = ref<CartItem[]>([])
const checkoutLoading = ref(false)
let pendingCheckout: AbortController | null = null

const orders = ref<OrderSummary[]>([])
const ordersLoading = ref(false)
const ordersError = ref('')
const cancellingOrderIds = ref<number[]>([])
const notice = ref<Notice | null>(null)
let noticeTimer: number | undefined
let pendingOrders: AbortController | null = null
let ordersVersion = 0

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
  if (checkoutLoading.value) return
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
  if (checkoutLoading.value) return
  const nextQuantity = item.quantity + change
  if (nextQuantity < 1) {
    removeFromCart(item.id)
    return
  }
  item.quantity = Math.min(nextQuantity, item.stock)
}

function removeFromCart(productId: number) {
  if (checkoutLoading.value) return
  cart.value = cart.value.filter((item) => item.id !== productId)
}

async function checkout() {
  if (checkoutLoading.value) return
  if (cart.value.length === 0) {
    showNotice('请先选择商品', 'error')
    return
  }
  const controller = new AbortController()
  pendingCheckout = controller
  checkoutLoading.value = true
  try {
    const order = await props.createOrder(
      cart.value.map((item) => ({
        productId: item.id,
        quantity: item.quantity,
      })),
      controller.signal,
    )
    if (controller.signal.aborted) return
    cart.value = []
    showNotice(`订单 #${order.id} 创建成功`, 'success')
    emit('navigate', 'orders')
  } catch (error) {
    if (controller.signal.aborted) return
    showNotice(
      error instanceof Error ? error.message : '创建订单失败',
      'error',
    )
  } finally {
    checkoutLoading.value = false
    pendingCheckout = null
  }
}

function cancelPendingOrders() {
  ordersVersion += 1
  pendingOrders?.abort()
  pendingOrders = null
  ordersLoading.value = false
}

async function loadOrders() {
  cancelPendingOrders()
  const version = ordersVersion
  const controller = new AbortController()
  pendingOrders = controller
  ordersLoading.value = true
  ordersError.value = ''
  orders.value = []
  try {
    const result = await props.fetchOrders(controller.signal)
    if (version !== ordersVersion || controller.signal.aborted) return
    orders.value = result
  } catch (error) {
    if (version !== ordersVersion || controller.signal.aborted) return
    ordersError.value = error instanceof Error ? error.message : '订单加载失败'
  } finally {
    if (version === ordersVersion) {
      ordersLoading.value = false
      pendingOrders = null
    }
  }
}

async function handleCancelOrder(order: OrderSummary) {
  if (cancellingOrderIds.value.includes(order.id)) return
  cancellingOrderIds.value = [...cancellingOrderIds.value, order.id]
  try {
    const updatedOrder = await props.cancelOrder(order.id)
    order.status = updatedOrder.status
    showNotice(`订单 #${order.id} 已取消`, 'success')
  } catch (error) {
    showNotice(
      error instanceof Error ? error.message : '取消订单失败',
      'error',
    )
  } finally {
    cancellingOrderIds.value = cancellingOrderIds.value.filter(id => id !== order.id)
  }
}

watch(() => props.view, (view) => {
  if (view === 'products' && products.value.length === 0) void loadProducts()
  if (view === 'orders') void loadOrders()
  else cancelPendingOrders()
}, { immediate: true })

onUnmounted(() => {
  pendingCheckout?.abort()
  window.clearTimeout(noticeTimer)
  cancelPendingOrders()
})
</script>

<template>
  <div :class="['commerce-panel', { 'is-storefront': layout === 'storefront' }]">
    <header v-if="layout === 'storefront'" class="storefront-heading">
      <div class="storefront-hero">
        <div>
          <span class="eyebrow">SMARTMALL · 用心挑选</span>
          <h1>{{ currentView === 'products' ? '好物，慢慢选。' : '看看你的订单。' }}</h1>
          <p>{{ currentView === 'products' ? '没有纷扰，从你需要的一件好物开始。' : '查看订单状态，或继续挑选喜欢的商品。' }}</p>
        </div>
        <div class="storefront-aside" aria-hidden="true">
          <span>CHAT + SHOP</span>
          <p>聊一聊，或逛一逛。<br />购物可以很简单。</p>
        </div>
      </div>
      <nav class="storefront-nav" aria-label="商城导航">
        <div class="storefront-tabs">
          <button
            type="button"
            :class="{ active: currentView === 'products' }"
            :aria-current="currentView === 'products' ? 'page' : undefined"
            @click="emit('navigate', 'products')"
          >全部商品</button>
          <button
            type="button"
            :class="{ active: currentView === 'orders' }"
            :aria-current="currentView === 'orders' ? 'page' : undefined"
            @click="emit('navigate', 'orders')"
          >订单记录</button>
        </div>
        <a v-if="currentView === 'products'" class="cart-link" href="#storefront-cart">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
            <path d="M5 7h14l1 14H4L5 7Z" stroke-linejoin="round" />
            <path d="M9 8V6a3 3 0 0 1 6 0v2" stroke-linecap="round" />
          </svg>
          <span>购物车</span>
          <span class="cart-count">{{ cartCount }}</span>
        </a>
      </nav>
    </header>
    <p class="demo-warning">查询、下单和取消已使用登录身份；订单详情的归属校验仍待完善，请勿用于真实交易。</p>
    <div v-if="currentView === 'products'" class="page-grid">
      <section class="catalog">
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
              <small>商品图片待补充</small>
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
                  :disabled="checkoutLoading || product.status !== 1 || product.stock < 1"
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

      <aside :id="layout === 'storefront' ? 'storefront-cart' : undefined" class="cart-panel">
        <div class="section-heading compact">
          <div>
            <span class="eyebrow">CART</span>
            <h2>购物车</h2>
          </div>
          <span>{{ cartCount }} 件</span>
        </div>

        <div v-if="cart.length === 0" class="empty-cart">
          <span>购物袋还是空的</span>
          <p>选择一个商品加入购物车。</p>
        </div>

        <div v-else class="cart-items">
          <article v-for="item in cart" :key="item.id" class="cart-item">
            <div>
              <strong>{{ item.name }}</strong>
              <span>{{ formatPrice(item.price) }}</span>
            </div>
            <div class="quantity-control">
              <button type="button" :disabled="checkoutLoading" @click="changeQuantity(item, -1)">−</button>
              <span>{{ item.quantity }}</span>
              <button type="button" :disabled="checkoutLoading" @click="changeQuantity(item, 1)">+</button>
              <button class="remove" type="button" :disabled="checkoutLoading" @click="removeFromCart(item.id)">
                移除
              </button>
            </div>
          </article>
        </div>

        <div class="checkout-box">
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
    </div>

    <div v-else class="orders-page">
      <h2>我的订单</h2>

      <div class="order-query">
        <p>显示当前登录账号的订单</p>
        <button class="primary" type="button" :disabled="ordersLoading" @click="loadOrders">
          {{ ordersLoading ? '正在加载…' : '刷新订单' }}
        </button>
      </div>

      <div v-if="ordersLoading" class="state-card" role="status">正在加载订单...</div>
      <div v-else-if="ordersError" class="state-card error-state" role="alert">
        <strong>订单加载失败</strong>
        <span>{{ ordersError }}</span>
        <button type="button" @click="loadOrders">重新加载</button>
      </div>
      <div v-else-if="orders.length === 0" class="state-card">你还没有订单</div>

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
            :disabled="order.status !== 'PENDING_PAYMENT' || cancellingOrderIds.includes(order.id)"
            @click="handleCancelOrder(order)"
          >
            {{ cancellingOrderIds.includes(order.id) ? '取消中…' : order.status === 'PENDING_PAYMENT' ? '取消订单' : '不可取消' }}
          </button>
        </article>
      </div>
    </div>

    <Transition name="toast">
      <div v-if="notice" role="status" :class="['toast', notice.tone]">
        {{ notice.message }}
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.page-grid { display: grid; grid-template-columns: minmax(0, 1fr) 360px; }
.catalog { min-width: 0; padding: 52px; border-right: 1px solid var(--line); }
.hero-copy { max-width: 850px; padding: 24px 0 46px; }

.eyebrow {
  color: var(--green);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.hero-copy h1 {
  max-width: 760px;
  margin: 16px 0;
  font-family: Georgia, 'Noto Serif SC', serif;
  font-size: clamp(42px, 6vw, 76px);
  font-weight: 500;
  line-height: 1.02;
  letter-spacing: -0.055em;
}

.hero-copy p {
  max-width: 660px;
  margin: 0;
  color: var(--muted);
  font-size: 17px;
  line-height: 1.8;
}

.filters {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 150px auto auto;
  gap: 12px;
  align-items: end;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: white;
}

label span { display: block; margin: 0 0 7px; color: var(--muted); font-size: 12px; font-weight: 600; }

input, select {
  width: 100%;
  height: 44px;
  padding: 0 13px;
  border: 1px solid #cfd2c9;
  border-radius: 12px;
  outline: none;
  color: var(--ink);
  background: #fbfaf5;
}

input:focus, select:focus { border-color: var(--green); box-shadow: 0 0 0 3px rgba(29, 107, 79, 0.12); }

.primary, .ghost, .filters button, .order-query button {
  height: 44px;
  padding: 0 18px;
  border-radius: 12px;
  font-weight: 600;
}

.primary { border: 1px solid var(--green); color: white; background: var(--green); }
.ghost { border: 1px solid var(--line); color: var(--ink); background: transparent; }

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  margin: 50px 0 22px;
}

.section-heading.compact { margin-top: 0; }
.section-heading h2 { margin: 6px 0 0; font-size: 28px; }
.section-heading > span { color: var(--muted); font-size: 14px; }

.product-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }

.product-card {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 22px;
  background: white;
  transition: transform 160ms ease, box-shadow 160ms ease;
}

.product-card:hover { transform: translateY(-3px); box-shadow: 0 18px 36px rgba(29, 48, 37, 0.1); }

.product-visual {
  display: flex;
  min-height: 150px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 22px;
  color: var(--green-dark);
  background: linear-gradient(135deg, rgba(216, 243, 107, 0.9), rgba(216, 243, 107, 0.12)), #e8efe9;
}

.product-visual span { font-size: 12px; font-weight: 700; }
.product-visual strong { align-self: center; margin-right: 38%; font-family: Georgia, serif; font-size: 64px; font-weight: 500; }
.product-body { padding: 22px; }
.product-meta { display: flex; justify-content: space-between; color: var(--muted); font-size: 12px; }

.status-dot::before {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 7px;
  border-radius: 50%;
  background: #3da879;
  content: '';
}

.status-dot.muted::before { background: #a9aaa4; }
.product-body h3 { margin: 18px 0 8px; font-size: 22px; }
.product-body p { min-height: 48px; margin: 0; color: var(--muted); font-size: 14px; line-height: 1.65; }
.product-action { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 22px; }
.product-action strong { font-size: 21px; }

.product-action button, .order-card button, .state-card button {
  padding: 10px 14px;
  border: 0;
  border-radius: 11px;
  color: white;
  background: var(--ink);
  font-weight: 600;
}

.pagination { display: flex; align-items: center; justify-content: center; gap: 16px; margin-top: 26px; color: var(--muted); font-size: 13px; }
.pagination button { padding: 8px 13px; border: 1px solid var(--line); border-radius: 10px; background: white; }

.cart-panel {
  position: sticky;
  top: 0;
  align-self: start;
  min-height: 720px;
  padding: 38px 28px;
  background: #f7f5ed;
}

.empty-cart, .state-card {
  display: grid;
  min-height: 150px;
  place-content: center;
  gap: 8px;
  padding: 28px;
  border: 1px dashed #c9cabe;
  border-radius: 18px;
  color: var(--muted);
  text-align: center;
}

.empty-cart span { color: var(--ink); font-weight: 700; }
.empty-cart p { margin: 0; font-size: 13px; }
.cart-items { display: grid; gap: 12px; }
.cart-item { padding: 16px; border: 1px solid var(--line); border-radius: 16px; background: white; }
.cart-item > div:first-child { display: flex; justify-content: space-between; gap: 12px; }
.cart-item span { color: var(--muted); font-size: 13px; }
.quantity-control { display: flex; align-items: center; gap: 9px; margin-top: 14px; }
.quantity-control button { width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 9px; background: #f7f6f0; }
.quantity-control .remove { width: auto; margin-left: auto; padding: 0 8px; border: 0; color: var(--danger); background: transparent; font-size: 12px; }

.checkout-box { display: grid; gap: 18px; margin-top: 28px; padding-top: 24px; border-top: 1px solid var(--line); }
.cart-total { display: flex; align-items: center; justify-content: space-between; }
.cart-total strong { font-family: Georgia, serif; font-size: 28px; }
.checkout-button { min-height: 52px; border: 0; border-radius: 14px; color: white; background: var(--green); font-weight: 700; }

.orders-page { min-height: 720px; padding: 52px; }
.orders-hero { padding-bottom: 28px; }
.orders-hero h1 { font-size: clamp(38px, 5vw, 64px); }
.order-query { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 16px; background: white; }
.order-query p { margin: 0; color: var(--muted); font-size: 13px; }
.order-list { display: grid; gap: 12px; margin-top: 28px; }

.order-card {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr auto;
  align-items: center;
  gap: 24px;
  padding: 20px 22px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: white;
}

.order-card span, .order-card strong { display: block; }
.order-card span { margin-bottom: 5px; color: var(--muted); font-size: 12px; }
.order-status { color: var(--green); font-size: 13px; }
.state-card { margin-top: 24px; }
.error-state { color: var(--danger); background: #fff7f3; }
.state-card button { justify-self: center; margin-top: 8px; }

.toast {
  position: fixed;
  right: 28px;
  bottom: 28px;
  z-index: 20;
  max-width: min(420px, calc(100vw - 40px));
  padding: 14px 18px;
  border-radius: 14px;
  color: white;
  background: var(--green-dark);
  box-shadow: 0 18px 40px rgba(23, 35, 29, 0.22);
}

.toast.error { background: var(--danger); }
.toast-enter-active, .toast-leave-active { transition: opacity 180ms ease, transform 180ms ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }

@media (max-width: 1050px) {
  .page-grid { grid-template-columns: 1fr; }
  .catalog { border-right: 0; }
  .cart-panel { position: static; min-height: auto; border-top: 1px solid var(--line); }
}


.page-grid { grid-template-columns: 1fr; }
.catalog, .orders-page { padding: 0; border: 0; min-height: auto; }
.filters { grid-template-columns: 1fr 1fr; padding: 14px; }
.filters label:first-child { grid-column: 1 / -1; }
.product-grid { grid-template-columns: 1fr; }
.product-visual { display: none; }
.section-heading { margin-top: 26px; }
.section-heading h2 { font-size: 20px; }
.cart-panel { position: static; min-height: auto; margin-top: 24px; padding: 20px; border: 1px solid var(--line); border-radius: 16px; background: #fafafa; }
.order-card { grid-template-columns: 1fr 1fr; gap: 16px; padding: 16px; }
.order-card button { grid-column: 1 / -1; }
.demo-warning { color: #855c2a; background: #fbf6ed; padding: 12px; border-radius: 10px; font-size: 12px; line-height: 1.7; margin: 0 0 20px; }
.product-body, .order-card, .cart-item { overflow-wrap: anywhere; }
.product-action { flex-wrap: wrap; }
.toast { position: sticky; bottom: 0; right: auto; margin-top: 16px; }

/* The same commerce state can be shown as a compact drawer or a full storefront. */
.is-storefront { min-width: 0; }
.storefront-heading { margin-bottom: 24px; }
.storefront-hero { display: flex; align-items: center; justify-content: space-between; gap: 32px; padding: 26px 0 38px; }
.storefront-hero h1 { margin: 16px 0 14px; font-size: clamp(32px, 4vw, 54px); font-weight: 500; letter-spacing: -1.6px; line-height: 1.25; }
.storefront-hero p { margin: 0; color: var(--muted); font-size: 14px; line-height: 1.8; }
.storefront-aside { padding-left: 30px; border-left: 1px solid #dce3d9; }
.storefront-aside > span { color: #74856f; font-size: 11px; font-weight: 600; letter-spacing: .16em; }
.storefront-aside p { margin-top: 10px; color: #56634f; font-size: 15px; }
.storefront-nav { display: flex; justify-content: space-between; align-items: center; gap: 16px; border-bottom: 1px solid #dde2d9; }
.storefront-tabs { display: flex; gap: 28px; }
.storefront-tabs button { padding: 14px 0 17px; border: 0; border-bottom: 2px solid transparent; color: var(--muted); background: transparent; font-size: 14px; }
.storefront-tabs button.active { border-bottom-color: var(--green); color: var(--green-dark); font-weight: 600; }
.cart-link { display: inline-flex; align-items: center; gap: 8px; min-height: 44px; color: var(--ink); font-size: 13px; text-decoration: none; }
.cart-link:hover { color: var(--green); }
.cart-link:focus-visible { outline: 2px solid var(--green); outline-offset: 4px; border-radius: 4px; }
.cart-link svg { width: 20px; height: 20px; }
.cart-count { display: grid; min-width: 23px; height: 23px; padding: 0 6px; place-content: center; border-radius: 50px; color: var(--green-dark); background: #e5eddf; font-size: 11px; font-weight: 600; }
.is-storefront .demo-warning { margin-bottom: 26px; padding: 10px 14px; border: 1px solid #ece6d9; background: #faf7ef; color: #7d7058; }
.is-storefront .page-grid { grid-template-columns: minmax(0, 1fr) 296px; gap: 28px; align-items: start; }
.is-storefront .filters { grid-template-columns: minmax(0, 1fr) 126px auto auto; gap: 10px; padding: 14px; border-radius: 16px; }
.is-storefront .filters label:first-child { grid-column: auto; }
.is-storefront .filters label { min-width: 0; }
.is-storefront .filters input, .is-storefront .filters select { min-width: 0; background: #f8f9f6; }
.is-storefront .section-heading { margin: 30px 0 18px; }
.is-storefront .section-heading h2 { margin-top: 5px; font-size: 23px; font-weight: 600; letter-spacing: -.5px; }
.is-storefront .section-heading .eyebrow { color: #7a8475; font-size: 10px; letter-spacing: .12em; }
.is-storefront .section-heading > span { font-size: 12px; }
.is-storefront .product-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.is-storefront .product-card { border-radius: 18px; border-color: #e3e7de; }
.is-storefront .product-card:hover { box-shadow: 0 12px 28px #34462d0c; }
.is-storefront .product-visual { position: relative; display: flex; height: 166px; min-height: 0; padding: 16px; color: #6b7b63; background: #edf1e6; }
.is-storefront .product-card:nth-child(3n + 2) .product-visual { color: #8e7d65; background: #f3eee5; }
.is-storefront .product-card:nth-child(3n) .product-visual { color: #6c8080; background: #eaf0ee; }
.is-storefront .product-visual > span { color: inherit; font-size: 10px; letter-spacing: .04em; }
.is-storefront .product-visual strong { position: absolute; inset: 0; display: grid; place-content: center; margin: 0; font-family: 'Microsoft YaHei', sans-serif; font-size: 52px; font-weight: 400; }
.is-storefront .product-visual small { position: absolute; bottom: 13px; right: 14px; font-size: 10px; opacity: .85; }
.is-storefront .product-body { display: flex; flex-direction: column; min-height: 198px; padding: 18px; }
.is-storefront .product-meta { flex-wrap: wrap; gap: 8px; font-size: 11px; }
.is-storefront .product-body h3 { margin: 16px 0 8px; font-size: 17px; font-weight: 600; }
.is-storefront .product-body p { flex: 1; min-height: 43px; font-size: 12px; line-height: 1.8; }
.is-storefront .product-action { gap: 10px; margin-top: 19px; }
.is-storefront .product-action strong { font-size: 18px; letter-spacing: -.4px; }
.is-storefront .product-action button { padding: 9px 11px; border: 1px solid #dfe6d9; color: #395737; background: #edf3e7; font-size: 11px; }
.is-storefront .product-action button:hover:not(:disabled) { background: #dfead6; }
.is-storefront .cart-panel { position: sticky; top: 24px; min-width: 0; margin-top: 0; padding: 22px; border-color: #e1e6db; border-radius: 18px; background: #fff; scroll-margin-top: 24px; }
.is-storefront .cart-panel .section-heading { margin: 0 0 22px; }
.is-storefront .cart-panel .section-heading h2 { font-size: 20px; }
.is-storefront .empty-cart { min-height: 150px; padding: 20px 10px; border-color: #d9e0d1; background: #f8faf5; font-size: 13px; }
.is-storefront .empty-cart p { font-size: 12px; }
.is-storefront .cart-item { padding: 12px; border-radius: 12px; background: #fafbf8; font-size: 12px; }
.is-storefront .cart-item > div:first-child { gap: 8px; }
.is-storefront .cart-item > div:first-child > strong { min-width: 0; }
.is-storefront .cart-item > div:first-child > span { flex-shrink: 0; }
.is-storefront .quantity-control { gap: 8px; }
.is-storefront .quantity-control button { flex-shrink: 0; }
.is-storefront .cart-total strong { font-family: inherit; font-size: 24px; font-weight: 600; }
.is-storefront .checkout-button { min-height: 48px; border-radius: 12px; font-size: 13px; }
.is-storefront .orders-page > h2 { margin: 8px 0 20px; font-size: 23px; font-weight: 600; }
.is-storefront .order-card { grid-template-columns: repeat(3, minmax(0, 1fr)) auto; padding: 22px; }
.is-storefront .order-card button { grid-column: auto; }
.is-storefront .toast { position: fixed; right: 28px; bottom: 96px; margin-top: 0; font-size: 13px; }

@media (max-width: 1200px) {
  .is-storefront .product-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .is-storefront .page-grid { gap: 22px; grid-template-columns: minmax(0, 1fr) 280px; }
  .is-storefront .filters { grid-template-columns: minmax(0, 1fr) auto auto; }
  .is-storefront .filters label:first-child { grid-column: 1 / -1; }
}
@media (max-width: 960px) {
  .is-storefront .page-grid { grid-template-columns: minmax(0, 1fr); gap: 30px; }
  .is-storefront .filters { grid-template-columns: minmax(0, 1fr) 126px auto auto; }
  .is-storefront .filters label:first-child { grid-column: auto; }
  .is-storefront .cart-panel { position: static; }
}
@media (max-width: 600px) {
  .storefront-hero { padding: 12px 0 28px; }
  .storefront-hero h1 { margin: 14px 0 10px; font-size: 32px; letter-spacing: -1px; }
  .storefront-hero .eyebrow { font-size: 10px; }
  .storefront-hero p { font-size: 12px; }
  .storefront-aside { display: none; }
  .storefront-heading { margin-bottom: 18px; }
  .storefront-nav { gap: 10px; }
  .storefront-tabs { gap: 21px; }
  .storefront-tabs button { font-size: 13px; }
  .cart-link { gap: 5px; font-size: 12px; }
  .cart-link svg { width: 18px; height: 18px; }
  .is-storefront .demo-warning { margin-bottom: 20px; font-size: 11px; }
  .is-storefront .filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .is-storefront .filters label { grid-column: 1 / -1; }
  .is-storefront .product-grid { grid-template-columns: minmax(0, 1fr); gap: 18px; }
  .is-storefront .product-visual { height: 186px; }
  .is-storefront .product-body { min-height: 0; padding: 20px; }
  .is-storefront .product-body h3 { font-size: 18px; }
  .is-storefront .product-action strong { font-size: 20px; }
  .is-storefront .product-action button { font-size: 12px; }
  .is-storefront .cart-panel { padding: 20px; }
  .is-storefront .order-card { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 18px; gap: 20px 12px; }
  .is-storefront .order-card button { grid-column: 1 / -1; }
  .is-storefront .toast { right: 16px; bottom: 88px; max-width: calc(100vw - 32px); }
}
</style>
