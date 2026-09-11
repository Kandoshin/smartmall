<script setup lang="ts">
defineProps<{ busy: boolean; error: string; status: string }>()
const username = defineModel<string>('username', { required: true })
const password = defineModel<string>('password', { required: true })
defineEmits<{ submit: [] }>()
</script>

<template>
  <div class="login-content" :aria-busy="busy">
    <div class="login-mark" aria-hidden="true">S</div>
    <h1 id="login-title">登录 SmartMall</h1>
    <p class="login-subtitle">让购物，回到简单。</p>
    <form class="auth-form" @submit.prevent="$emit('submit')">
      <label for="login-username"><span>用户名</span>
        <input id="login-username" v-model="username" name="username" autocomplete="username"
               placeholder="请输入用户名" required autofocus :disabled="busy" />
      </label>
      <label for="login-password"><span>密码</span>
        <input id="login-password" v-model="password" name="password" type="password"
               autocomplete="current-password" placeholder="请输入密码" required :disabled="busy" />
      </label>
      <p v-if="error" class="error-text" role="alert">{{ error }}</p>
      <button class="primary login-submit" type="submit" :disabled="busy">{{ busy ? '处理中…' : '登录' }}</button>
      <p class="login-status" role="status">{{ status }}</p>
    </form>
    <p class="login-note">请使用已注册的账号。未主动退出且刷新凭证有效时，可自动恢复登录。</p>
  </div>
</template>

<style scoped>
.login-mark { width: 42px; height: 42px; display: grid; place-items: center; margin: 0 auto 22px; border-radius: 13px; background: #262a27; color: white; font-size: 24px; font-weight: 600; }
h1 { margin: 0; text-align: center; font-size: 26px; font-weight: 600; letter-spacing: -.04em; }
.login-subtitle { text-align: center; color: var(--muted); margin: 10px 0 30px; font-size: 14px; }
.auth-form { display: grid; gap: 18px; }
.login-submit { width: 100%; margin-top: 4px; }
.login-status { margin: 0; font-size: 13px; color: var(--muted); }
.login-status:empty { display: none; }
.login-note { font-size: 12px; line-height: 1.8; color: var(--muted); text-align: center; margin: 24px 0 0; }
</style>
