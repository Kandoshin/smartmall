<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import {
  cancelOrder as cancelOrderRequest,
  createOrder,
  getOrdersByUserId,
  getProducts,
} from '../api'
import type { CartItem, OrderSummary, Product } from '../types'

type ViewName = 'products' | 'orders'
type Notice = { message: string; tone: 'success' | 'error' }

const props = defineProps<{ view: ViewName }>()
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
    emit('navigate', 'orders')
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

watch(() => props.view, (view) => {
  if (view === 'products' && products.value.length === 0) void loadProducts()
  if (view === 'orders') void loadOrders()
}, { immediate: true })

onUnmounted(() => window.clearTimeout(noticeTimer))
</script>

<template>
  <div class="commerce-panel">
    <p class="demo-warning">教学演示：订单仍按手填用户 ID 操作，后端尚未校验订单归属。请勿用于真实交易。</p>
    <main v-if="currentView === 'products'" class="page-grid">
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
          <p>选择一个商品加入购物车。</p>
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
      <h2>订单信息</h2>

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

.filters, .order-query {
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
.order-query { grid-template-columns: minmax(180px, 300px) auto; justify-content: start; }
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
.filters, .order-query { grid-template-columns: 1fr 1fr; padding: 14px; }
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
</style>
