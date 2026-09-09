// Optional browser smoke check. Uses Playwright supplied by the environment, not a production dependency.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SMARTMALL_PLAYWRIGHT_MODULE || 'playwright')
const browser = await chromium.launch({ headless: true, channel: process.env.SMARTMALL_BROWSER || 'chrome' })
const baseUrl = process.env.SMARTMALL_BASE_URL || 'http://127.0.0.1:5173'
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', error => pageErrors.push(error.message))

const user = { id: 42, username: '浏览器测试用户', email: null }
let mode = 'success'
let lastAuthorization
let meCalls = 0
let loginCalls = 0
let productCalls = 0
let productMode = 'success'
let orderCalls = 0
let orderMode = 'empty'
const createdOrder = { id: 7, userId: 1, totalAmount: 299.9, status: 'PENDING_PAYMENT' }
let credentialLeak = false
let releaseDelayedMe
const json = (route, status, data, message = '操作成功') => route.fulfill({
  status, contentType: 'application/json', body: JSON.stringify({ code: status, message, data }),
})

await page.route('**/api/products**', route => {
  productCalls += 1
  assert.equal(route.request().headers()['x-xsrf-token'], undefined)
  assert.equal((route.request().headers().cookie || '').includes('smartmall_csrf'), false)
  if (productMode === 'failed') return json(route, 500, null, '商品服务暂不可用')
  if (route.request().headers().authorization) credentialLeak = true
  return json(route, 200, { current: 1, size: 8, total: 1, pages: 1, records: [
    { id: 1, name: '机械键盘', description: '浏览器测试商品', price: 299.9, stock: 10, status: 1 },
  ] })
})
await page.route('**/api/orders**', route => {
  orderCalls += 1
  assert.equal(route.request().headers().authorization, undefined)
  assert.equal(route.request().headers()['x-xsrf-token'], undefined)
  assert.equal((route.request().headers().cookie || '').includes('smartmall_csrf'), false)
  if (route.request().method() === 'POST') {
    assert.deepEqual(route.request().postDataJSON(), { userId: 1, items: [{ productId: 1, quantity: 1 }] })
    orderMode = 'created'
    return json(route, 200, createdOrder)
  }
  if (route.request().method() === 'PATCH') {
    assert.equal(new URL(route.request().url()).pathname, '/api/orders/7/cancel')
    createdOrder.status = 'CANCELLED'
    return json(route, 200, createdOrder)
  }
  if (orderMode === 'failed') return json(route, 500, null, '订单服务暂不可用')
  return json(route, 200, orderMode === 'created' ? [createdOrder] : [])
})
await page.route('**/api/auth/csrf', route => {
  assert.equal(route.request().method(), 'GET')
  assert.equal(route.request().postData(), null)
  assert.equal(route.request().headers().authorization, undefined)
  if (mode === 'csrf-unavailable') return route.fulfill({ status: 502, body: '' })
  return route.fulfill({
    status: 200, contentType: 'application/json',
    headers: { 'Set-Cookie': 'smartmall_csrf=browser-csrf-cookie; Path=/api/auth; HttpOnly; SameSite=Strict' },
    body: JSON.stringify({ code: 200, data: { headerName: 'X-XSRF-TOKEN', token: 'browser-csrf-token' } }),
  })
})
await page.route('**/api/auth/login', route => {
  loginCalls += 1
  assert.equal(route.request().method(), 'POST')
  assert.equal(route.request().headers()['x-xsrf-token'], 'browser-csrf-token')
  assert.equal((route.request().headers().cookie || '').includes('smartmall_csrf=browser-csrf-cookie'), true)
  assert.deepEqual(route.request().postDataJSON(), { username: 'alice', password: 'test-password' })
  assert.equal(route.request().headers().authorization, undefined)
  if (mode === 'wrong-password') return json(route, 401, null, '用户名或密码错误')
  if (mode === 'unavailable') return route.fulfill({ status: 502, body: '' })
  return json(route, 200, {
    accessToken: 'browser-test-token', expiresIn: mode === 'short-lived' ? 1 : 900,
    user: { ...user, username: '不应显示登录响应里的名字' },
  })
})
await page.route('**/api/auth/me', async route => {
  meCalls += 1
  lastAuthorization = route.request().headers().authorization
  assert.equal(lastAuthorization, 'Bearer browser-test-token')
  assert.equal(new URL(route.request().url()).search, '')
  if (mode === 'me-rejected') return json(route, 401, null, '请先登录或重新登录')
  if (mode === 'delayed-me') {
    await new Promise(resolve => { releaseDelayedMe = resolve })
  }
  try { await json(route, 200, user) } catch { /* Request may be aborted by logout. */ }
})

async function submitLogin() {
  await page.getByLabel('用户名', { exact: true }).fill('alice')
  await page.getByLabel('密码', { exact: true }).fill('test-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

async function expectLoggedIn() {
  await page.getByRole('dialog', { name: '登录 SmartMall' }).waitFor({ state: 'hidden' })
  if (await page.getByRole('button', { name: '打开侧边栏' }).getAttribute('aria-expanded') === 'false') {
    await page.getByRole('button', { name: '打开侧边栏' }).click()
  }
  await page.getByRole('button', { name: '个人信息', exact: true }).click()
  assert.equal(await page.locator('.profile-name').innerText(), user.username)
  assert.equal(await page.getByText('不应显示登录响应里的名字').count(), 0)
}

try {
  await page.goto(baseUrl)
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(productCalls, 0)
  assert.equal(orderCalls, 0)
  await page.keyboard.press('Escape')
  assert.equal(await page.getByRole('dialog', { name: '登录 SmartMall' }).isVisible(), true)
  // The browser traps focus inside the modal; Tab cannot enter the background.
  for (let i = 0; i < 6; i++) {
    await page.keyboard.press('Tab')
    // Chromium may move focus to browser chrome (document.body) when cycling.
    assert.equal(await page.evaluate(() => document.activeElement === document.body ||
      !!document.activeElement?.closest('dialog')), true)
  }
  mode = 'csrf-unavailable'
  await submitLogin()
  await page.getByRole('alert').filter({ hasText: '后端已启动' }).waitFor()
  assert.equal(loginCalls, 0)
  assert.equal(meCalls, 0)
  console.log('PASS failed CSRF bootstrap prevents credential submission')
  mode = 'wrong-password'
  await submitLogin()
  await page.getByRole('alert').filter({ hasText: '用户名或密码错误' }).waitFor()
  assert.equal(await page.getByLabel('密码', { exact: true }).inputValue(), '')
  assert.equal(meCalls, 0)
  console.log('PASS wrong password does not authenticate and clears password input')

  mode = 'success'
  await submitLogin()
  await expectLoggedIn()
  assert.equal(lastAuthorization, 'Bearer browser-test-token')
  assert.deepEqual(await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) })),
    { local: [], session: [] })
  const cookies = await context.cookies()
  assert.equal(cookies.length, 1)
  assert.equal(cookies[0].name, 'smartmall_csrf')
  assert.equal(cookies[0].httpOnly, true)
  assert.equal(cookies[0].sameSite, 'Strict')
  assert.equal(cookies[0].path, '/api/auth')
  assert.equal((await page.evaluate(() => document.cookie)).includes('smartmall_csrf'), false)
  await page.getByRole('button', { name: '确认当前身份', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '当前身份验证成功' }).waitFor()
  await page.getByRole('button', { name: '商品信息', exact: true }).click()
  await page.getByRole('heading', { name: '机械键盘' }).waitFor()
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByRole('heading', { name: '机械键盘' }).waitFor()
  assert.equal(credentialLeak, false)
  console.log('PASS real UI uses /auth/me identity, sends Bearer header, and does not persist/leak token')

  await mkdir('artifacts', { recursive: true })
  await page.screenshot({ path: 'artifacts/chat-products-desktop.png', fullPage: true })
  productMode = 'failed'
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await page.getByText('商品服务暂不可用', { exact: true }).waitFor()
  productMode = 'success'
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await page.getByRole('heading', { name: '机械键盘' }).waitFor()
  await page.getByRole('button', { name: '订单信息', exact: true }).click()
  await page.getByText('该用户暂无订单').waitFor()
  orderMode = 'failed'
  await page.getByRole('button', { name: '查询订单', exact: true }).click()
  await page.getByText('订单服务暂不可用', { exact: true }).waitFor()
  await page.getByRole('button', { name: '商品信息', exact: true }).click()
  await page.getByRole('button', { name: '加入购物车', exact: true }).click()
  await page.getByRole('button', { name: '提交订单', exact: true }).click()
  await page.getByText('#7', { exact: true }).waitFor()
  await page.getByRole('button', { name: '取消订单', exact: true }).click()
  await page.getByText('已取消', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '不可取消', exact: true }).isDisabled(), true)
  console.log('PASS existing cart checkout, order tab transition and cancellation with mocked services')
  await page.getByRole('button', { name: '个人信息', exact: true }).click()
  await page.screenshot({ path: 'artifacts/chat-profile-desktop.png', fullPage: true })
  await page.keyboard.press('Escape')
  assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).getAttribute('aria-expanded'), 'false')
  assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).evaluate(el => el === document.activeElement), true)
  await page.screenshot({ path: 'artifacts/chat-desktop.png', fullPage: true })
  assert.equal(await page.getByRole('button', { name: '发送消息', exact: true }).isEnabled(), false)
  const chat = page.getByRole('textbox', { name: '发送消息', exact: true })
  await chat.fill('找一个键盘')
  await chat.press('Shift+Enter')
  assert.equal((await chat.inputValue()).includes('\n'), true)
  await chat.press('Enter')
  await page.getByText('当前是对话界面预览', { exact: false }).waitFor()
  assert.equal(await chat.inputValue(), '')
  console.log('PASS modal focus, lazy data, drawer sections, product retry, order empty/error, chat preview and keyboard')
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
  await page.screenshot({ path: 'artifacts/chat-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '打开侧边栏' }).click()
  await page.getByRole('button', { name: '商品信息', exact: true }).click()
  await page.getByRole('heading', { name: '机械键盘' }).waitFor()
  assert.equal(await page.locator('dialog.drawer').evaluate(el => el.scrollWidth <= el.clientWidth), true)
  await page.screenshot({ path: 'artifacts/chat-products-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '关闭侧边栏' }).click()
  await page.setViewportSize({ width: 1280, height: 900 })
  console.log('PASS desktop and mobile layout without horizontal overflow')

  await page.reload()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(await page.getByLabel('密码', { exact: true }).inputValue(), '')
  await page.screenshot({ path: 'artifacts/auth-login-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
  await page.screenshot({ path: 'artifacts/auth-login-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1280, height: 900 })
  console.log('PASS reload loses memory-only session')

  mode = 'unavailable'
  await submitLogin()
  await page.getByRole('alert').filter({ hasText: '后端已启动' }).waitFor()
  console.log('PASS unavailable backend displays a retryable message')

  mode = 'me-rejected'
  await submitLogin()
  await page.getByRole('alert').filter({ hasText: '请先登录或重新登录' }).waitFor()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  console.log('PASS login token rejected by /auth/me never shows authenticated user')

  mode = 'short-lived'
  await submitLogin()
  await expectLoggedIn()
  await page.getByRole('alert').filter({ hasText: '登录已过期' }).waitFor()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(await page.getByRole('dialog', { name: '你的空间' }).isVisible(), false)
  console.log('PASS token lifetime clears local session and prompts login')

  mode = 'success'
  await submitLogin()
  await expectLoggedIn()
  mode = 'me-rejected'
  await page.getByRole('button', { name: '确认当前身份', exact: true }).click()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  console.log('PASS later 401 clears an existing session')

  mode = 'success'
  await submitLogin()
  await expectLoggedIn()
  mode = 'delayed-me'
  const requested = page.waitForRequest('**/api/auth/me')
  await page.getByRole('button', { name: '确认当前身份', exact: true }).click()
  await requested
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  // Allow the response after logout; it must not restore the previous identity.
  releaseDelayedMe?.()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  mode = 'success'
  await submitLogin()
  await expectLoggedIn()
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.deepEqual(pageErrors, [])
  console.log('PASS logout, pending-request cancellation, and subsequent login; no browser runtime errors')
} finally {
  releaseDelayedMe?.()
  await browser.close()
}
