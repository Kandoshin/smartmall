<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AuthPanel from './components/AuthPanel.vue'
import CommercePanel from './components/CommercePanel.vue'
import ModalSurface from './components/ModalSurface.vue'
import { useAuth } from './composables/useAuth'

const { username, password, currentUser, busy, errorMessage, statusMessage,
  handleLogin, refreshCurrentUser, logout } = useAuth()
type Section = 'profile' | 'products' | 'orders'
const sidebarOpen = ref(false)
const section = ref<Section>('profile')
const commerceView = ref<'products' | 'orders'>('products')
const commerceVisited = ref(false)
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
  <div class="chat-shell">
    <header class="chat-header">
      <span class="wordmark">SmartMall<span class="wordmark-dot">.</span></span>
      <button class="icon-button menu-toggle" type="button" aria-label="打开侧边栏"
              aria-controls="account-sidebar" :aria-expanded="sidebarOpen"
              :disabled="!currentUser" @click="sidebarOpen = true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
          <rect x="3" y="4" width="18" height="16" rx="3" /><path d="M15 4v16M7 9h4M7 13h4" />
        </svg>
      </button>
    </header>

    <main :class="['chat-main', { 'has-messages': messages.length }]">
      <div v-if="!messages.length" class="chat-welcome">
        <h1>今天，想找点什么？</h1>
      </div>
      <section v-else class="conversation" aria-label="当前对话" aria-live="polite">
        <article v-for="message in messages" :key="message.id" class="message-pair">
          <p class="user-message">{{ message.text }}</p>
          <div class="assistant-message">
            <span class="assistant-mark" aria-hidden="true">S</span>
            <p>当前是对话界面预览，尚未接入 AI，不会执行搜索、下单或退款。你可以从右上角侧边栏查看商品和订单演示。</p>
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
          <button class="ghost full-width" type="button" :disabled="busy" @click="refreshCurrentUser">
            {{ busy ? '正在确认…' : '确认当前身份' }}
          </button>
          <button class="logout-button" type="button" @click="logout">退出登录</button>
          <p class="profile-note">登录仅保留在当前页面，刷新后需重新登录。退出仅清除本页凭证，不会撤销已签发的 JWT。</p>
        </section>
        <CommercePanel v-if="currentUser && commerceVisited" v-show="section !== 'profile'"
                       :view="commerceView" @navigate="selectSection" />
      </div>
    </ModalSurface>

    <ModalSurface :open="!currentUser" labelledby="login-title" :dismissible="false">
      <AuthPanel v-model:username="username" v-model:password="password"
                 :busy="busy" :error="errorMessage" :status="statusMessage" @submit="handleLogin" />
    </ModalSurface>
  </div>
</template>
