<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AuthPanel from './components/AuthPanel.vue'
import CommercePanel from './components/CommercePanel.vue'
import ModalSurface from './components/ModalSurface.vue'
import { useAuth } from './composables/useAuth'

const { username, password, currentUser, busy, errorMessage, statusMessage, logoutNeedsRetry,
  restoring, restoreNeedsRetry, restoreSession, handleLogin, logout, fetchMyOrders, createMyOrder, cancelMyOrder } = useAuth()
type Section = 'profile' | 'products' | 'orders'
const pageMode = ref<'ai' | 'mall'>('ai')
const sidebarOpen = ref(false)
const section = ref<Section>('profile')
const commerceView = ref<'products' | 'orders'>('products')
const commerceVisited = ref(false)
// One commerce instance moves between its two display locations, keeping the same cart.
const commerceLayout = computed(() =>
  sidebarOpen.value && section.value !== 'profile' ? 'drawer'
    : pageMode.value === 'mall' ? 'storefront' : 'drawer',
)
const composer = ref<HTMLTextAreaElement | null>(null)
const draft = ref('')
const messages = ref<{ id: number; text: string }[]>([])
let messageId = 0

function selectSection(value: Section) {
  section.value = value
  if (value !== 'profile') {
    commerceView.value = value
    commerceVisited.value = true
  }
}

async function togglePageMode() {
  if (!currentUser.value) return
  sidebarOpen.value = false
  section.value = 'profile'
  pageMode.value = pageMode.value === 'ai' ? 'mall' : 'ai'
  if (pageMode.value === 'mall') {
    commerceView.value = 'products'
    commerceVisited.value = true
  }
  await nextTick()
  window.scrollTo({ top: 0, behavior: 'auto' })
  if (pageMode.value === 'ai') composer.value?.focus({ preventScroll: true })
  else document.getElementById('storefront-view')?.focus({ preventScroll: true })
}

function sendMessage() {
  const text = draft.value.trim()
  if (!currentUser.value || !text) return
  messages.value.push({ id: ++messageId, text })
  draft.value = ''
  void nextTick(() => {
    document.querySelector('.conversation-end')?.scrollIntoView({ behavior: 'smooth', block: 'end' })
    composer.value?.focus()
  })
}

function composerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    sendMessage()
  }
}

watch(currentUser, async (user) => {
  if (!user) {
    pageMode.value = 'ai'
    sidebarOpen.value = false
    section.value = 'profile'
    commerceVisited.value = false
    messages.value = []
    draft.value = ''
  } else {
    await nextTick()
    composer.value?.focus()
  }
})
</script>

<template>
  <div :class="['chat-shell', { 'mall-mode': pageMode === 'mall' }]">
    <header class="chat-header">
      <div class="brand-lockup">
        <span class="wordmark">SmartMall<span class="wordmark-dot">.</span></span>
        <span v-if="pageMode === 'mall'" class="mode-caption">商城首页</span>
      </div>
      <button class="icon-button menu-toggle" type="button" aria-label="打开侧边栏"
              aria-controls="account-sidebar" :aria-expanded="sidebarOpen"
              :disabled="!currentUser" @click="sidebarOpen = true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
          <rect x="3" y="4" width="18" height="16" rx="3" /><path d="M15 4v16M7 9h4M7 13h4" />
        </svg>
      </button>
    </header>

    <Transition name="page-view">
    <main v-show="pageMode === 'ai'" id="ai-view" :class="['chat-main', { 'has-messages': messages.length }]">
      <div v-if="!messages.length" class="chat-welcome">
        <h1>今天，想找点什么？</h1>
      </div>
      <section v-else class="conversation" aria-label="当前对话" aria-live="polite">
        <article v-for="message in messages" :key="message.id" class="message-pair">
          <p class="user-message">{{ message.text }}</p>
          <div class="assistant-message">
            <span class="assistant-mark" aria-hidden="true">S</span>
            <p>当前是对话界面预览，尚未接入 AI，不会执行搜索、下单或退款。你可以点击右下角“逛商城”浏览商品，也可以从侧边栏查看订单演示。</p>
          </div>
        </article>
        <div class="conversation-end" />
      </section>

      <div class="composer-area">
        <form class="composer" @submit.prevent="sendMessage">
          <label class="sr-only" for="chat-input">发送消息</label>
          <textarea id="chat-input" ref="composer" v-model="draft" rows="2" maxlength="4000"
                    placeholder="说说你需要什么…" :disabled="!currentUser" @keydown="composerKeydown" />
          <div class="composer-bottom">
            <span>SmartMall · 对话购物</span>
            <button class="send-button" type="submit" aria-label="发送消息"
                    :disabled="!currentUser || !draft.trim()">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M12 19V5m-6 6 6-6 6 6" />
              </svg>
            </button>
          </div>
        </form>
        <p class="composer-note">AI 功能尚未接入 · 当前对话仅保留在本页</p>
      </div>
    </main>
    </Transition>

    <Transition name="page-view">
      <main v-show="pageMode === 'mall'" id="storefront-view" class="storefront-view"
            aria-label="商城首页" tabindex="-1">
        <div id="storefront-commerce" />
      </main>
    </Transition>

    <button class="page-mode-toggle" type="button" :disabled="!currentUser"
            :aria-controls="pageMode === 'ai' ? 'storefront-view' : 'ai-view'" @click="togglePageMode">
      <svg v-if="pageMode === 'ai'" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M5 8h14l1 12H4L5 8Z" /><path d="M8 8V6a4 4 0 0 1 8 0v2" />
      </svg>
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
           stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M20 11.5a8 8 0 0 1-8 8H5l-3 2v-10a9 9 0 0 1 18 0Z" />
        <path d="M7 11h8m-8 4h5" />
      </svg>
      <span>{{ pageMode === 'ai' ? '逛商城' : '回到 AI' }}</span>
    </button>

    <ModalSurface :open="sidebarOpen && !!currentUser" labelledby="sidebar-title" drawer
                  @dismiss="sidebarOpen = false">
      <div id="account-sidebar">
        <div class="drawer-heading">
          <h2 id="sidebar-title">你的空间</h2>
          <button class="icon-button" type="button" aria-label="关闭侧边栏" autofocus @click="sidebarOpen = false">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
          </button>
        </div>
        <nav class="drawer-nav" aria-label="侧边栏导航">
          <button v-for="item in ([
            { key: 'profile', label: '个人信息' },
            { key: 'products', label: '商品信息' },
            { key: 'orders', label: '订单信息' },
          ] as const)" :key="item.key" type="button" :class="{ active: section === item.key }"
                  :aria-current="section === item.key ? 'page' : undefined" @click="selectSection(item.key)">
            {{ item.label }}
          </button>
        </nav>

        <section v-if="currentUser && section === 'profile'" class="profile-section" aria-label="个人信息">
          <div class="profile-avatar" aria-hidden="true">{{ currentUser.username.slice(0, 1).toUpperCase() }}</div>
          <h3 class="profile-name">{{ currentUser.username }}</h3>
          <p class="profile-caption">很高兴在这里见到你</p>
          <dl class="profile-details">
            <div><dt>用户 ID</dt><dd>{{ currentUser.id }}</dd></div>
            <div><dt>邮箱</dt><dd>{{ currentUser.email || '未填写邮箱' }}</dd></div>
          </dl>
          <p v-if="errorMessage" class="error-text" role="alert">{{ errorMessage }}</p>
          <p class="profile-status" role="status">{{ statusMessage }}</p>
          <button class="logout-button" type="button" @click="logout">退出登录</button>
          <p class="profile-note">刷新凭证有效时，重新打开页面可恢复登录。主动退出会清除本页身份和浏览器刷新凭证，但不会撤销已经被复制的 JWT。</p>
        </section>
        <div id="drawer-commerce" />
      </div>
    </ModalSurface>

    <Teleport v-if="currentUser && commerceVisited"
              :to="commerceLayout === 'storefront' ? '#storefront-commerce' : '#drawer-commerce'">
      <CommercePanel v-show="commerceLayout === 'storefront' || section !== 'profile'"
                     :key="currentUser.id" :fetch-orders="fetchMyOrders" :create-order="createMyOrder"
                     :cancel-order="cancelMyOrder"
                     :view="commerceView" :layout="commerceLayout" @navigate="selectSection" />
    </Teleport>

    <ModalSurface :open="!restoring && !currentUser" labelledby="login-title" :dismissible="false">
      <AuthPanel v-model:username="username" v-model:password="password"
                 :busy="busy" :error="errorMessage" :status="statusMessage" @submit="handleLogin" />
      <button v-if="restoreNeedsRetry" class="ghost full-width" type="button"
              :disabled="busy" @click="restoreSession">重试恢复登录</button>
      <button v-if="logoutNeedsRetry" class="ghost full-width" type="button" :disabled="busy" @click="logout">
        {{ busy ? '正在退出…' : '重试退出' }}
      </button>
    </ModalSurface>
  </div>
</template>
