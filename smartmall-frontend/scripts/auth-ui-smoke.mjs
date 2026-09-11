// Optional browser smoke check. Uses Playwright supplied by the environment, not a production dependency.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SMARTMALL_PLAYWRIGHT_MODULE || 'playwright')
const browser = await chromium.launch({ headless: true, channel: process.env.SMARTMALL_BROWSER || 'chrome' })
const baseUrl = process.env.SMARTMALL_BASE_URL || 'http://127.0.0.1:5173'
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const pageErrors = []
context.on('page', openedPage => openedPage.on('pageerror', error => pageErrors.push(error.message)))
const page = await context.newPage()
let pageNavigations = 0
page.on('framenavigated', frame => { if (frame === page.mainFrame()) pageNavigations += 1 })

const user = { id: 42, username: '浏览器测试用户', email: null }
let mode = 'success'
let lastAuthorization
let meCalls = 0
let loginCalls = 0
let refreshCalls = 0
let refreshMode = 'success'
let releaseDelayedRefresh
let logoutCalls = 0
let logoutMode = 'success'
let releaseDelayedLogout
let productCalls = 0
let productMode = 'success'
let lastProductName = ''
let orderCalls = 0
let orderMode = 'empty'
const fixtureOrder = { id: 7, totalAmount: 299.9, status: 'PENDING_PAYMENT' }
let credentialLeak = false
const json = (route, status, data, message = '操作成功') => route.fulfill({
  status, contentType: 'application/json', body: JSON.stringify({ code: status, message, data }),
})

// Never let an unexpected API call reach a live backend during browser checks.
await context.route('**/api/**', route => route.abort('blockedbyclient'))
await context.route('**/api/products**', route => {
  productCalls += 1
  lastProductName = new URL(route.request().url()).searchParams.get('name') || ''
  assert.equal(route.request().headers()['x-xsrf-token'], undefined)
  assert.equal((route.request().headers().cookie || '').includes('smartmall_csrf'), false)
  assert.equal((route.request().headers().cookie || '').includes('smartmall_refresh'), false)
  if (productMode === 'failed') return json(route, 500, null, '商品服务暂不可用')
  if (productMode === 'empty') return json(route, 200, { current: 1, size: 8, total: 0, pages: 0, records: [] })
  if (route.request().headers().authorization) credentialLeak = true
  const records = [
    { id: 1, name: '机械键盘', description: '浏览器测试商品', price: 299.9, stock: 10, status: 1 },
  ]
  if (productMode === 'dense') records.push(
    { id: 2, name: '人体工学办公座椅加厚透气可调节靠背舒适长名称展示款', description: '窄屏长名称与缺货边界检查', price: 699, stock: 0, status: 1 },
    { id: 3, name: '陶瓷马克杯', description: '日常饮水用品', price: 39.9, stock: 6, status: 1 },
    { id: 4, name: '便携阅读灯', description: '小巧灯具', price: 89, stock: 12, status: 1 },
  )
  return json(route, 200, { current: 1, size: 8, total: records.length, pages: 1, records })
})
await context.route('**/api/orders**', route => {
  orderCalls += 1
  assert.equal(route.request().headers()['x-xsrf-token'], undefined)
  assert.equal((route.request().headers().cookie || '').includes('smartmall_csrf'), false)
  assert.equal((route.request().headers().cookie || '').includes('smartmall_refresh'), false)
  if (route.request().method() === 'POST') {
    assert.equal(route.request().headers().authorization, undefined)
    assert.deepEqual(route.request().postDataJSON(), { userId: 1, items: [{ productId: 1, quantity: 1 }] })
    return json(route, 403, null, '订单写操作尚未迁移')
  }
  if (route.request().method() === 'PATCH') {
    assert.equal(route.request().headers().authorization, undefined)
    assert.equal(new URL(route.request().url()).pathname, '/api/orders/7/cancel')
    return json(route, 403, null, '订单写操作尚未迁移')
  }
  assert.equal(route.request().method(), 'GET')
  assert.equal(new URL(route.request().url()).pathname, '/api/orders/me')
  assert.equal(new URL(route.request().url()).search, '')
  assert.ok(['Bearer browser-test-token', 'Bearer browser-restored-token'].includes(route.request().headers().authorization))
  if (orderMode === 'failed') return json(route, 500, null, '订单服务暂不可用')
  return json(route, 200, orderMode === 'fixture' ? [fixtureOrder] : [])
})
await context.route('**/api/auth/csrf', route => {
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
await context.route('**/api/auth/login', route => {
  loginCalls += 1
  assert.equal(route.request().method(), 'POST')
  assert.equal(route.request().headers()['x-xsrf-token'], 'browser-csrf-token')
  assert.equal((route.request().headers().cookie || '').includes('smartmall_csrf=browser-csrf-cookie'), true)
  assert.deepEqual(route.request().postDataJSON(), { username: 'alice', password: 'test-password' })
  assert.equal(route.request().headers().authorization, undefined)
  if (mode === 'wrong-password') return json(route, 401, null, '用户名或密码错误')
  if (mode === 'unavailable') return route.fulfill({ status: 502, body: '' })
  return route.fulfill({
    status: 200, contentType: 'application/json',
    headers: { 'Set-Cookie': 'smartmall_refresh=browser-refresh-cookie; Path=/api/auth; Max-Age=86400; HttpOnly; SameSite=Strict' },
    body: JSON.stringify({ code: 200, data: {
      accessToken: 'browser-test-token', expiresIn: mode === 'short-lived' ? 1 : 900,
      user: { ...user, username: '不应显示登录响应里的名字' },
    } }),
  })
})
await context.route('**/api/auth/refresh', async route => {
  refreshCalls += 1
  assert.equal(route.request().method(), 'POST')
  assert.equal(route.request().postData(), null)
  assert.equal(route.request().headers().authorization, undefined)
  assert.equal(route.request().headers()['x-xsrf-token'], 'browser-csrf-token')
  const sentCookies = route.request().headers().cookie || ''
  assert.equal(sentCookies.includes('smartmall_csrf=browser-csrf-cookie'), true)
  const requestedMode = refreshMode
  if (requestedMode === 'network-failed') return route.abort('failed')
  if (requestedMode === 'unavailable') return json(route, 503, null, '测试服务暂不可用')
  if (requestedMode === 'csrf-rejected') return json(route, 403, null, '无权访问该资源')
  if (!sentCookies.includes('smartmall_refresh=browser-refresh-cookie') || requestedMode === 'expired') {
    return json(route, 401, null, '登录已失效，请重新登录')
  }
  if (requestedMode === 'delayed') await new Promise(resolve => { releaseDelayedRefresh = resolve })
  let data = {
    accessToken: 'browser-restored-token', expiresIn: 900,
    user: { ...user, username: '不应显示刷新响应里的名字' },
  }
  if (requestedMode === 'malformed-token') data.accessToken = { token: 'invalid-shape' }
  if (requestedMode === 'malformed-expiry') data.expiresIn = -1
  // No Set-Cookie: obtaining an Access Token must not rotate/extend the refresh credential.
  try { await json(route, 200, data) } catch { /* The page may close during restoration. */ }
})
await context.route('**/api/auth/logout', async route => {
  logoutCalls += 1
  assert.equal(route.request().method(), 'POST')
  assert.equal(route.request().postData(), null)
  assert.equal(route.request().headers().authorization, undefined)
  assert.equal(route.request().headers()['x-xsrf-token'], 'browser-csrf-token')
  assert.equal((route.request().headers().cookie || '').includes('smartmall_csrf=browser-csrf-cookie'), true)
  if (logoutMode === 'unavailable') return json(route, 503, null, '测试服务暂不可用')
  if (logoutMode === 'csrf-rejected') return json(route, 403, null, '无权访问该资源')
  if (logoutMode === 'delayed') await new Promise(resolve => { releaseDelayedLogout = resolve })
  return route.fulfill({
    status: 200, contentType: 'application/json',
    headers: { 'Set-Cookie': 'smartmall_refresh=; Path=/api/auth; Max-Age=0; HttpOnly; SameSite=Strict' },
    body: JSON.stringify({ code: 200, message: '操作成功', data: null }),
  })
})
await context.route('**/api/auth/me', async route => {
  meCalls += 1
  lastAuthorization = route.request().headers().authorization
  assert.ok(['Bearer browser-test-token', 'Bearer browser-restored-token'].includes(lastAuthorization))
  assert.equal(new URL(route.request().url()).search, '')
  if (mode === 'me-rejected') return json(route, 401, null, '请先登录或重新登录')
  await json(route, 200, user)
})

async function submitLogin() {
  await page.getByLabel('用户名', { exact: true }).fill('alice')
  await page.getByLabel('密码', { exact: true }).fill('test-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

async function expectLoggedIn(target = page) {
  await target.getByRole('button', { name: '打开侧边栏' }).and(target.locator(':enabled')).waitFor()
  await target.getByRole('dialog', { name: '登录 SmartMall' }).waitFor({ state: 'hidden' })
  if (await target.getByRole('button', { name: '打开侧边栏' }).getAttribute('aria-expanded') === 'false') {
    await target.getByRole('button', { name: '打开侧边栏' }).click()
  }
  await target.getByRole('button', { name: '个人信息', exact: true }).click()
  assert.equal(await target.locator('.profile-name').innerText(), user.username)
  assert.equal(await target.getByText('不应显示登录响应里的名字').count(), 0)
  assert.equal(await target.getByText('不应显示刷新响应里的名字').count(), 0)
  assert.equal(await target.getByRole('button', { name: '确认当前身份', exact: true }).count(), 0)
}

async function getRefreshCookie() {
  return (await context.cookies()).find(cookie => cookie.name === 'smartmall_refresh')
}

try {
  await page.goto(baseUrl)
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(refreshCalls, 1)
  assert.equal(loginCalls, 0)
  assert.equal(meCalls, 0)
  assert.equal(await page.getByRole('button', { name: '重试恢复登录', exact: true }).count(), 0)
  assert.equal(productCalls, 0)
  assert.equal(orderCalls, 0)
  console.log('PASS first visit without refresh Cookie attempts restoration then shows normal login')
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
  assert.equal(productCalls, 0)
  assert.equal(orderCalls, 0)
  assert.equal(lastAuthorization, 'Bearer browser-test-token')
  assert.deepEqual(await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) })),
    { local: [], session: [] })
  const cookies = await context.cookies()
  assert.deepEqual(cookies.map(cookie => cookie.name).sort(), ['smartmall_csrf', 'smartmall_refresh'])
  for (const cookie of cookies) {
    assert.equal(cookie.httpOnly, true)
    assert.equal(cookie.sameSite, 'Strict')
    assert.equal(cookie.path, '/api/auth')
    assert.equal((await page.evaluate(() => document.cookie)).includes(cookie.name), false)
  }
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
  await page.getByText('你还没有订单', { exact: true }).waitFor()
  assert.equal(await page.locator('.orders-page input').count(), 0)
  orderMode = 'failed'
  await page.getByRole('button', { name: '刷新订单', exact: true }).click()
  await page.getByText('订单服务暂不可用', { exact: true }).waitFor()
  await page.getByRole('button', { name: '商品信息', exact: true }).click()
  await page.getByRole('button', { name: '加入购物车', exact: true }).click()
  await page.getByRole('button', { name: '提交订单', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '订单写操作尚未迁移' }).waitFor()
  assert.equal(await page.locator('.cart-item').count(), 1)
  assert.equal(await page.locator('.quantity-control > span').innerText(), '1')
  await page.getByRole('button', { name: '移除', exact: true }).click()
  orderMode = 'fixture'
  await page.getByRole('button', { name: '订单信息', exact: true }).click()
  await page.getByRole('button', { name: '刷新订单', exact: true }).click()
  await page.getByText('#7', { exact: true }).waitFor()
  const cancelResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/orders/7/cancel')
  await page.getByRole('button', { name: '取消订单', exact: true }).click()
  assert.equal((await cancelResponse).status(), 403)
  await page.getByRole('status').filter({ hasText: '订单写操作尚未迁移' }).waitFor()
  await page.getByText('待支付', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '取消订单', exact: true }).isEnabled(), true)
  console.log('PASS unmigrated checkout/cancellation fail without Bearer; failed checkout retains cart and order stays pending')
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

  const authBeforeModeSwitch = { login: loginCalls, refresh: refreshCalls, me: meCalls, navigation: pageNavigations }
  const mall = page.locator('#storefront-view')
  await page.getByRole('button', { name: '逛商城', exact: true }).click()
  await mall.getByRole('heading', { name: '机械键盘' }).waitFor()
  await page.locator('.chat-main').waitFor({ state: 'hidden' })
  assert.equal(await page.locator('.chat-main').isVisible(), false)
  assert.equal(await mall.getAttribute('aria-label'), '商城首页')
  assert.equal((await mall.boundingBox()).width > 900, true)
  productMode = 'dense'
  await mall.getByLabel('商品名称', { exact: true }).fill('键盘')
  await mall.getByRole('button', { name: '查询', exact: true }).click()
  await mall.locator('.product-card').nth(3).waitFor()
  assert.equal(lastProductName, '键盘')
  assert.equal(await mall.locator('.product-card').nth(1).getByRole('button', { name: '加入购物车', exact: true }).isDisabled(), true)
  await mall.locator('.product-card').first().getByRole('button', { name: '加入购物车', exact: true }).click()
  assert.equal(await mall.locator('.cart-item').count(), 1)
  await page.screenshot({ path: 'artifacts/mall-desktop.png', fullPage: true })

  await page.getByRole('button', { name: '回到 AI', exact: true }).click()
  await chat.fill('切换模式后继续聊天')
  await chat.press('Enter')
  await page.getByText('切换模式后继续聊天', { exact: true }).waitFor()
  await chat.fill('尚未发送的草稿')
  await page.getByRole('button', { name: '逛商城', exact: true }).click()
  await mall.getByRole('heading', { name: '机械键盘' }).waitFor()
  assert.equal(await mall.getByLabel('商品名称', { exact: true }).inputValue(), '键盘')
  assert.equal(await mall.locator('.cart-item').count(), 1)
  assert.equal(await mall.locator('.quantity-control > span').innerText(), '1')
  await page.getByRole('button', { name: '回到 AI', exact: true }).click()
  assert.equal(await chat.inputValue(), '尚未发送的草稿')
  assert.equal(await page.getByText('切换模式后继续聊天', { exact: true }).isVisible(), true)
  console.log('PASS AI/mall switching preserves chat, draft, search and cart without a page reload')

  await page.getByRole('button', { name: '打开侧边栏' }).click()
  await page.getByRole('button', { name: '商品信息', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '你的空间' })
  assert.equal(await drawer.getByLabel('商品名称', { exact: true }).inputValue(), '键盘')
  assert.equal(await drawer.locator('.cart-item').count(), 1)
  await drawer.getByRole('button', { name: '+', exact: true }).click()
  await page.getByRole('button', { name: '订单信息', exact: true }).click()
  await page.getByText('#7', { exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭侧边栏' }).click()
  await page.getByRole('button', { name: '逛商城', exact: true }).click()
  await mall.getByRole('button', { name: '全部商品', exact: true }).click()
  assert.equal(await mall.locator('.quantity-control > span').innerText(), '2')
  await mall.getByRole('button', { name: '订单记录', exact: true }).click()
  await mall.getByText('#7', { exact: true }).waitFor()
  await mall.getByRole('heading', { name: '看看你的订单。', exact: true }).waitFor()
  await mall.getByRole('button', { name: '全部商品', exact: true }).click()
  assert.equal(await mall.locator('.quantity-control > span').innerText(), '2')
  assert.equal(await page.locator('.commerce-panel').count(), 1)
  assert.deepEqual({ login: loginCalls, refresh: refreshCalls, me: meCalls, navigation: pageNavigations }, authBeforeModeSwitch)
  console.log('PASS storefront and original product/order sidebar share one cart without extra authentication calls')

  productMode = 'empty'
  await mall.getByRole('button', { name: '查询', exact: true }).click()
  await mall.getByText('暂无匹配商品', { exact: true }).waitFor()
  productMode = 'failed'
  await mall.getByRole('button', { name: '查询', exact: true }).click()
  await mall.getByText('商品服务暂不可用', { exact: true }).waitFor()
  productMode = 'dense'
  await mall.getByRole('button', { name: '重新加载', exact: true }).click()
  await mall.locator('.product-card').nth(3).waitFor()
  assert.equal(await mall.locator('.quantity-control > span').innerText(), '2')
  console.log('PASS storefront shows empty/error states and retries without discarding the cart')

  await page.setViewportSize({ width: 390, height: 844 })
  await page.evaluate(() => window.scrollTo(0, 0))
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const backToAi = page.getByRole('button', { name: '回到 AI', exact: true })
  assert.equal(await backToAi.evaluate(button => {
    const box = button.getBoundingClientRect()
    return box.left >= 0 && box.right <= innerWidth && box.top >= 0 && box.bottom <= innerHeight &&
      button.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
  }), true)
  await page.screenshot({ path: 'artifacts/mall-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 320, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.setViewportSize({ width: 390, height: 844 })
  await mall.getByRole('button', { name: '提交订单', exact: true }).scrollIntoViewIfNeeded()
  assert.equal(await mall.getByRole('button', { name: '提交订单', exact: true }).evaluate(button => {
    const box = button.getBoundingClientRect()
    return button.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
  }), true)
  await page.screenshot({ path: 'artifacts/mall-cart-mobile.png' })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await backToAi.focus()
  await backToAi.press('Enter')
  await chat.waitFor({ state: 'visible' })
  const goToMall = page.getByRole('button', { name: '逛商城', exact: true })
  await goToMall.focus()
  await goToMall.press('Space')
  await mall.getByRole('heading', { name: '机械键盘' }).waitFor()
  assert.equal(await page.evaluate(() => document.getAnimations().some(animation =>
    animation.playState === 'running' && Number(animation.effect?.getTiming().duration) > 0)), false)
  await page.getByRole('button', { name: '回到 AI', exact: true }).click()
  await page.emulateMedia({ reducedMotion: 'no-preference' })
  await page.setViewportSize({ width: 1280, height: 900 })
  productMode = 'success'
  console.log('PASS mobile storefront has no horizontal overflow or blocked checkout and switches with keyboard/reduced motion')

  const cookieBeforeReload = await getRefreshCookie()
  const loginBeforeRestore = loginCalls
  const refreshBeforeReload = refreshCalls
  await page.reload()
  await expectLoggedIn()
  assert.equal(refreshCalls, refreshBeforeReload + 1)
  assert.equal(loginCalls, loginBeforeRestore)
  assert.equal(lastAuthorization, 'Bearer browser-restored-token')
  assert.deepEqual(await getRefreshCookie(), cookieBeforeReload)
  assert.equal(logoutCalls, 0)
  assert.deepEqual(await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) })),
    { local: [], session: [] })
  console.log('PASS reload restores verified identity with new memory-only Access Token and unchanged refresh Cookie')

  const refreshBeforeNewTab = refreshCalls
  const nextTab = await context.newPage()
  try {
    await nextTab.goto(baseUrl)
    await expectLoggedIn(nextTab)
    assert.equal(refreshCalls, refreshBeforeNewTab + 1)
    assert.equal(loginCalls, loginBeforeRestore)
    assert.deepEqual(await getRefreshCookie(), cookieBeforeReload)
    assert.deepEqual(await nextTab.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) })),
      { local: [], session: [] })
  } finally {
    await nextTab.close()
  }
  assert.equal(logoutCalls, 0)
  assert.deepEqual(await getRefreshCookie(), cookieBeforeReload)
  console.log('PASS new tab restores from shared HttpOnly Cookie; closing tab does not log out')

  refreshMode = 'delayed'
  const delayedRestoreRequested = page.waitForRequest('**/api/auth/refresh')
  const productsBeforeRestore = productCalls
  const ordersBeforeRestore = orderCalls
  const meBeforeRestore = meCalls
  await page.reload()
  await delayedRestoreRequested
  assert.equal(await page.getByRole('dialog').count(), 0)
  assert.equal(await page.getByText('正在恢复登录', { exact: false }).isVisible(), false)
  assert.equal(await page.getByRole('heading', { name: '登录 SmartMall' }).isVisible(), false)
  assert.equal(await page.getByLabel('用户名', { exact: true }).isVisible(), false)
  assert.equal(await page.getByLabel('密码', { exact: true }).isVisible(), false)
  assert.equal(await page.getByRole('heading', { name: '今天，想找点什么？', exact: true }).isVisible(), true)
  assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).isDisabled(), true)
  assert.equal(await page.getByRole('textbox', { name: '发送消息', exact: true }).isDisabled(), true)
  assert.equal(await page.getByRole('button', { name: '发送消息', exact: true }).isDisabled(), true)
  assert.equal(await page.getByRole('button', { name: '逛商城', exact: true }).isDisabled(), true)
  assert.equal(productCalls, productsBeforeRestore)
  assert.equal(orderCalls, ordersBeforeRestore)
  assert.equal(meCalls, meBeforeRestore)
  await page.screenshot({ path: 'artifacts/auth-restoring-desktop.png', fullPage: true })
  releaseDelayedRefresh?.()
  await expectLoggedIn()
  refreshMode = 'success'
  console.log('PASS pending startup restoration stays on chat home without dialogs, enabled actions or business requests')

  for (const failure of ['network-failed', 'unavailable', 'csrf-rejected']) {
    refreshMode = failure
    const meBeforeFailure = meCalls
    await page.reload()
    await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
    await page.getByRole('button', { name: '重试恢复登录', exact: true }).waitFor()
    assert.equal(await page.getByRole('alert').count() > 0, true)
    assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).isDisabled(), true)
    assert.equal(await page.getByLabel('用户名', { exact: true }).isEnabled(), true)
    assert.equal(await page.getByLabel('密码', { exact: true }).isEnabled(), true)
    assert.equal(meCalls, meBeforeFailure)
    assert.deepEqual(await getRefreshCookie(), cookieBeforeReload)
    if (failure === 'unavailable') await page.screenshot({ path: 'artifacts/auth-restore-retry-desktop.png', fullPage: true })
    refreshMode = 'success'
    await page.getByRole('button', { name: '重试恢复登录', exact: true }).click()
    await expectLoggedIn()
    assert.equal(loginCalls, loginBeforeRestore)
    assert.equal(lastAuthorization, 'Bearer browser-restored-token')
  }
  console.log('PASS network/503/403 restoration failures preserve Cookie, allow manual login and recover on retry')

  for (const malformed of ['malformed-token', 'malformed-expiry']) {
    refreshMode = malformed
    const meBeforeMalformed = meCalls
    await page.reload()
    await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
    await page.getByRole('button', { name: '重试恢复登录', exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).isDisabled(), true)
    assert.equal(meCalls, meBeforeMalformed)
    assert.equal(await page.locator('.profile-name').count(), 0)
  }
  console.log('PASS malformed refresh token/expiry responses never reach /me or authenticate')

  refreshMode = 'expired'
  const meBeforeExpired = meCalls
  await page.reload()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(meCalls, meBeforeExpired)
  assert.equal(await page.getByRole('button', { name: '重试恢复登录', exact: true }).count(), 0)
  assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).isDisabled(), true)
  assert.equal(logoutCalls, 0)
  assert.equal(await page.getByLabel('密码', { exact: true }).inputValue(), '')
  await page.screenshot({ path: 'artifacts/auth-login-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true)
  await page.screenshot({ path: 'artifacts/auth-login-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1280, height: 900 })
  refreshMode = 'success'
  console.log('PASS expired refresh credential falls back to normal login without retry loop or logout call')

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
  const meBeforeRejectedRestore = meCalls
  await page.reload()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(meCalls, meBeforeRejectedRestore + 1)
  assert.equal(lastAuthorization, 'Bearer browser-restored-token')
  assert.equal(await page.locator('.profile-name').count(), 0)
  console.log('PASS restored token rejected by /auth/me does not restore identity')

  mode = 'success'
  await submitLogin()
  await expectLoggedIn()
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  await page.getByRole('status').filter({ hasText: '已退出登录' }).waitFor()
  assert.equal((await context.cookies()).some(cookie => cookie.name === 'smartmall_refresh'), false)
  console.log('PASS logout removes HttpOnly refresh cookie and clears the page identity')

  const meBeforeLoggedOutReload = meCalls
  const logoutBeforeReload = logoutCalls
  const refreshBeforeLoggedOutReload = refreshCalls
  await page.reload()
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(refreshCalls, refreshBeforeLoggedOutReload + 1)
  assert.equal(meCalls, meBeforeLoggedOutReload)
  assert.equal(logoutCalls, logoutBeforeReload)
  assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).isDisabled(), true)
  assert.equal(await getRefreshCookie(), undefined)
  console.log('PASS explicit logout followed by reload does not restore the former identity')

  for (const failure of ['unavailable', 'csrf-rejected']) {
    await submitLogin()
    await expectLoggedIn()
    logoutMode = failure
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.getByRole('alert').filter({ hasText: '未能确认清除浏览器登录凭证' }).waitFor()
    assert.equal(await page.getByRole('button', { name: '打开侧边栏' }).isDisabled(), true)
    assert.equal((await context.cookies()).some(cookie => cookie.name === 'smartmall_refresh'), true)
    if (failure === 'unavailable') await page.screenshot({ path: 'artifacts/logout-retry-desktop.png', fullPage: true })
    logoutMode = 'success'
    await page.getByRole('button', { name: '重试退出', exact: true }).click()
    await page.getByRole('status').filter({ hasText: '已退出登录' }).waitFor()
    assert.equal((await context.cookies()).some(cookie => cookie.name === 'smartmall_refresh'), false)
    assert.equal(await page.getByRole('button', { name: '重试退出', exact: true }).count(), 0)
  }
  console.log('PASS failed logout clears page state, warns honestly and retry deletes cookie')

  await submitLogin()
  await expectLoggedIn()
  logoutMode = 'delayed'
  const beforeLogout = logoutCalls
  const logoutRequested = page.waitForRequest('**/api/auth/logout')
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await logoutRequested
  await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  assert.equal(await page.getByLabel('用户名', { exact: true }).isDisabled(), true)
  assert.equal(await page.getByLabel('密码', { exact: true }).isDisabled(), true)
  assert.equal(await page.getByRole('button', { name: '处理中…', exact: true }).isDisabled(), true)
  releaseDelayedLogout?.()
  await page.getByRole('status').filter({ hasText: '已退出登录' }).waitFor()
  assert.equal(logoutCalls, beforeLogout + 1)
  assert.equal((await context.cookies()).some(cookie => cookie.name === 'smartmall_refresh'), false)
  console.log('PASS pending logout blocks new login until Cookie deletion response completes')

  logoutMode = 'success'
  await submitLogin()
  await expectLoggedIn()
  await page.getByRole('button', { name: '关闭侧边栏' }).click()
  await chat.fill('退出后应清空的草稿')
  await page.getByRole('button', { name: '逛商城', exact: true }).click()
  await mall.getByRole('heading', { name: '机械键盘' }).waitFor()
  await mall.getByLabel('商品名称', { exact: true }).fill('退出前筛选')
  await mall.getByRole('button', { name: '加入购物车', exact: true }).click()
  await page.getByRole('button', { name: '打开侧边栏' }).click()
  await page.getByRole('button', { name: '个人信息', exact: true }).click()
  await page.getByRole('button', { name: '退出登录', exact: true }).click()
  await page.getByRole('status').filter({ hasText: '已退出登录' }).waitFor()
  await mall.waitFor({ state: 'hidden' })
  assert.equal(await mall.isVisible(), false)
  assert.equal(await page.locator('.chat-main').isVisible(), true)
  assert.equal(await page.locator('.commerce-panel').count(), 0)
  await submitLogin()
  await expectLoggedIn()
  await page.getByRole('button', { name: '关闭侧边栏' }).click()
  assert.equal(await chat.inputValue(), '')
  assert.equal(await page.locator('.user-message').count(), 0)
  await page.getByRole('button', { name: '逛商城', exact: true }).click()
  await mall.getByRole('heading', { name: '机械键盘' }).waitFor()
  assert.equal(await mall.getByLabel('商品名称', { exact: true }).inputValue(), '')
  assert.equal(await mall.locator('.cart-item').count(), 0)
  await mall.getByText('购物袋还是空的', { exact: true }).waitFor()
  console.log('PASS logout from storefront resets AI view and the next login has no former cart, filters or draft')
  assert.deepEqual(pageErrors, [])
  console.log('PASS no browser runtime errors')
} finally {
  releaseDelayedRefresh?.()
  releaseDelayedLogout?.()
  await browser.close()
}
