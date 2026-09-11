// Real Vue/browser behavior with every API request mocked; no real account, key, or database.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SMARTMALL_PLAYWRIGHT_MODULE || 'playwright')
const browser = await chromium.launch({ headless: true, channel: process.env.SMARTMALL_BROWSER || 'chrome' })
const baseUrl = process.env.SMARTMALL_BASE_URL || 'http://127.0.0.1:5173'
let passed = 0

const users = {
  7: { id: 7, username: '订单用户甲', email: null },
  8: { id: 8, username: '订单用户乙', email: null },
}
const ordersFor = id => [{ id: id === 7 ? 7007 : 8008, totalAmount: 299.9, status: 'PENDING_PAYMENT' }]
const json = (route, status, data, message = '操作成功', headers = {}) => route.fulfill({
  status, contentType: 'application/json', headers, body: JSON.stringify({ code: status, message, data }),
})

async function waitForFixture(predicate) {
  const deadline = Date.now() + 6000
  while (!predicate()) {
    assert.ok(Date.now() < deadline, 'Timed out waiting for mocked API handler')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

async function fixture({ refreshUser, delayRefresh = false, expiresIn = 900 } = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  const page = await context.newPage()
  page.setDefaultTimeout(6000)
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  // Observe the actual fetch options and cancellation. One race test deliberately ignores
  // transport cancellation to model a response already received before account switching.
  await context.addInitScript(() => {
    window.__orderFetches = []
    window.__ignoreOrderAbort = false
    const nativeFetch = window.fetch.bind(window)
    window.fetch = (input, init = {}) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href)
      if (url.pathname !== '/api/orders/me') return nativeFetch(input, init)
      const entry = {
        url: url.href, credentials: init.credentials, cache: init.cache,
        authorization: new Headers(init.headers).get('Authorization'),
        aborted: false, settled: false,
      }
      window.__orderFetches.push(entry)
      init.signal?.addEventListener('abort', () => { entry.aborted = true }, { once: true })
      const options = window.__ignoreOrderAbort ? { ...init, signal: undefined } : init
      return nativeFetch(input, options).then(response => {
        entry.settled = true
        return response
      }, error => {
        entry.settled = true
        entry.errorName = error.name
        throw error
      })
    }
  })
  await context.addCookies([{ name: 'unrelated_browser_cookie', value: 'must-not-reach-orders',
    domain: new URL(baseUrl).hostname, path: '/', sameSite: 'Strict' }])
  if (refreshUser) await context.addCookies([{ name: 'smartmall_refresh', value: `refresh-${refreshUser}`,
    domain: new URL(baseUrl).hostname, path: '/api/auth', httpOnly: true, sameSite: 'Strict' }])

  const state = {
    orderMode: 'success', expiresIn, requests: [], pendingOrders: [],
    tokens: new Map(), issued: 0, refreshCalls: 0, loginCalls: 0, meCalls: 0,
    releaseRefresh: null, unexpected: [], lastToken: '',
  }
  function issueToken(id, prefix) {
    const token = `${prefix}-${id}-${++state.issued}`
    state.tokens.set(token, id)
    state.lastToken = token
    return { accessToken: token, expiresIn: state.expiresIn, user: users[id] }
  }
  await context.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const headers = request.headers()
    if (url.pathname === '/api/auth/csrf') {
      assert.equal(headers.authorization, undefined)
      return json(route, 200, { headerName: 'X-XSRF-TOKEN', token: 'order-test-mask' }, '操作成功',
        { 'Set-Cookie': 'smartmall_csrf=order-test-csrf; Path=/api/auth; HttpOnly; SameSite=Strict' })
    }
    if (url.pathname === '/api/auth/login') {
      state.loginCalls += 1
      assert.equal(request.method(), 'POST')
      assert.equal(headers.authorization, undefined)
      assert.equal(headers['x-xsrf-token'], 'order-test-mask')
      const id = request.postDataJSON().username === 'bob' ? 8 : 7
      return json(route, 200, issueToken(id, 'login'), '操作成功',
        { 'Set-Cookie': `smartmall_refresh=refresh-${id}; Path=/api/auth; HttpOnly; SameSite=Strict` })
    }
    if (url.pathname === '/api/auth/refresh') {
      state.refreshCalls += 1
      assert.equal(request.method(), 'POST')
      assert.equal(headers.authorization, undefined)
      assert.equal(headers['x-xsrf-token'], 'order-test-mask')
      const id = Number((headers.cookie || '').match(/smartmall_refresh=refresh-(7|8)/)?.[1])
      if (!id) return json(route, 401, null, '登录已失效，请重新登录')
      if (delayRefresh) await new Promise(resolve => { state.releaseRefresh = resolve })
      return json(route, 200, issueToken(id, 'restored'))
    }
    if (url.pathname === '/api/auth/me') {
      state.meCalls += 1
      const id = state.tokens.get(headers.authorization?.replace('Bearer ', ''))
      assert.ok(id)
      return json(route, 200, users[id])
    }
    if (url.pathname === '/api/auth/logout') {
      assert.equal(request.method(), 'POST')
      assert.equal(headers.authorization, undefined)
      assert.equal(headers['x-xsrf-token'], 'order-test-mask')
      return json(route, 200, null, '操作成功',
        { 'Set-Cookie': 'smartmall_refresh=; Max-Age=0; Path=/api/auth; HttpOnly; SameSite=Strict' })
    }
    if (url.pathname === '/api/orders/me') {
      assert.equal(request.method(), 'GET')
      assert.equal(url.search, '')
      assert.equal(headers.cookie, undefined, 'Orders must omit all browser Cookies, even root-path Cookies')
      assert.equal(headers['x-xsrf-token'], undefined)
      const token = headers.authorization?.replace('Bearer ', '')
      const id = state.tokens.get(token)
      assert.ok(id, 'Order requests must carry a token issued to this browser session')
      const responseMode = state.orderMode
      state.requests.push({ id, token, mode: responseMode })
      if (responseMode === 'delayed') {
        return new Promise(resolve => state.pendingOrders.push({ id, complete: async (status = 200) => {
          try { await json(route, status, status === 200 ? ordersFor(id) : null, '旧会话响应') }
          catch { /* A correctly canceled request cannot receive its delayed response. */ }
          finally { resolve() }
        } }))
      }
      if (responseMode === 'network') return route.abort('failed')
      if (responseMode === 'empty401') return route.fulfill({ status: 401, body: '' })
      if (responseMode === '401') return json(route, 401, null, '请先登录或重新登录')
      if (responseMode === '503') return json(route, 503, null, '订单服务暂不可用')
      return json(route, 200, responseMode === 'empty' ? [] : ordersFor(id))
    }
    if (url.pathname === '/api/products') {
      assert.equal(headers.authorization, undefined)
      assert.equal((headers.cookie || '').includes('smartmall_refresh'), false)
      assert.equal((headers.cookie || '').includes('smartmall_csrf'), false)
      return json(route, 200, { current: 1, size: 8, total: 0, pages: 0, records: [] })
    }
    state.unexpected.push(`${request.method()} ${url.pathname}${url.search}`)
    return route.abort('blockedbyclient')
  })

  const ordersPage = page.locator('.orders-page')
  async function loggedIn() {
    await page.getByRole('button', { name: '打开侧边栏' }).and(page.locator(':enabled')).waitFor()
    await page.getByRole('dialog', { name: '登录 SmartMall' }).waitFor({ state: 'hidden' })
  }
  async function login(username = 'alice') {
    await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
    await page.getByLabel('用户名', { exact: true }).fill(username)
    await page.getByLabel('密码', { exact: true }).fill('test-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await loggedIn()
  }
  async function openOrders() {
    const menu = page.getByRole('button', { name: '打开侧边栏' })
    if (await menu.getAttribute('aria-expanded') === 'false') await menu.click()
    await page.getByRole('button', { name: '订单信息', exact: true }).click()
    await ordersPage.getByRole('heading', { name: '我的订单', exact: true }).waitFor()
  }
  async function logout() {
    const menu = page.getByRole('button', { name: '打开侧边栏' })
    if (await menu.getAttribute('aria-expanded') === 'false') await menu.click()
    await page.getByRole('button', { name: '个人信息', exact: true }).click()
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await page.getByRole('status').filter({ hasText: '已退出登录' }).waitFor()
    await page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
  }
  async function settle() {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  }
  async function checkClean() {
    assert.deepEqual(errors, [])
    assert.deepEqual(state.unexpected, [])
    const metadata = await page.evaluate(() => window.__orderFetches)
    for (const request of metadata) {
      assert.equal(request.credentials, 'omit')
      assert.equal(request.cache, 'no-store')
      assert.ok(request.authorization?.startsWith('Bearer '))
      assert.equal(new URL(request.url).search, '')
    }
    assert.deepEqual(await page.evaluate(() => [Object.keys(localStorage), Object.keys(sessionStorage)]), [[], []])
  }
  return { context, page, state, ordersPage, login, loggedIn, openOrders, logout, settle, checkClean }
}

async function run(name, options, body) {
  const f = await fixture(options)
  try {
    await f.page.goto(baseUrl)
    await body(f)
    await f.checkClean()
    passed += 1
    console.log(`PASS ${name}`)
  } catch (error) {
    await mkdir('artifacts', { recursive: true })
    await f.page.screenshot({ path: 'artifacts/order-auth-failure.png', fullPage: true }).catch(() => {})
    throw error
  } finally {
    f.state.releaseRefresh?.()
    for (const pending of f.state.pendingOrders) await pending.complete()
    await f.context.close()
  }
}

try {
  await run('login identity supplies Bearer to My Orders without user-ID input, URL query, storage, or Cookies', {}, async f => {
    await f.page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
    assert.equal(f.state.requests.length, 0)
    await f.login()
    assert.equal(f.state.requests.length, 0, 'Orders remain lazy after login')
    await f.openOrders()
    await f.ordersPage.getByText('#7007', { exact: true }).waitFor()
    assert.equal(await f.ordersPage.locator('input').count(), 0)
    assert.equal(f.state.requests[0].token, f.state.lastToken)
    assert.equal(f.state.requests.length, 1)
    await mkdir('artifacts', { recursive: true })
    await f.page.screenshot({ path: 'artifacts/my-orders-desktop.png', fullPage: true })
    await f.page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    assert.equal(await f.page.locator('dialog.drawer').evaluate(el => el.scrollWidth <= el.clientWidth), true)
    await f.page.screenshot({ path: 'artifacts/my-orders-mobile.png', fullPage: true })
    await f.page.setViewportSize({ width: 320, height: 844 })
    assert.equal(await f.page.locator('dialog.drawer').evaluate(el => el.scrollWidth <= el.clientWidth), true)
    await f.page.setViewportSize({ width: 1280, height: 900 })
    await f.page.getByRole('button', { name: '关闭侧边栏' }).click()
    await f.page.getByRole('button', { name: '逛商城', exact: true }).click()
    await f.page.locator('#storefront-view').getByRole('button', { name: '订单记录', exact: true }).click()
    await f.ordersPage.getByText('#7007', { exact: true }).waitFor()
    await f.page.screenshot({ path: 'artifacts/my-orders-storefront-desktop.png', fullPage: true })
    await f.page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    await f.page.screenshot({ path: 'artifacts/my-orders-storefront-mobile.png', fullPage: true })
    await f.page.setViewportSize({ width: 320, height: 844 })
    assert.equal(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  })

  await run('Cookie restoration completes before order requests and uses the newly restored Access Token',
    { refreshUser: 7, delayRefresh: true }, async f => {
      await waitForFixture(() => f.state.releaseRefresh)
      await f.page.waitForFunction(() => !!document.querySelector('button[aria-label="打开侧边栏"]')?.disabled)
      assert.equal(f.state.requests.length, 0)
      assert.equal(f.state.meCalls, 0)
      assert.equal(await f.page.getByRole('button', { name: '逛商城', exact: true }).isDisabled(), true)
      f.state.releaseRefresh()
      await f.loggedIn()
      await f.openOrders()
      await f.ordersPage.getByText('#7007', { exact: true }).waitFor()
      assert.ok(f.state.requests[0].token.startsWith('restored-7-'))
      assert.equal(f.state.loginCalls, 0)
      assert.equal(f.state.refreshCalls, 1)
    })

  await run('empty current-user list has the new empty text and can refresh', {}, async f => {
    f.state.orderMode = 'empty'
    await f.login()
    await f.openOrders()
    await f.ordersPage.getByText('你还没有订单', { exact: true }).waitFor()
    await f.page.screenshot({ path: 'artifacts/my-orders-empty-desktop.png', fullPage: true })
    f.state.orderMode = 'success'
    await f.ordersPage.getByRole('button', { name: '刷新订单', exact: true }).click()
    await f.ordersPage.getByText('#7007', { exact: true }).waitFor()
    assert.equal(f.state.requests.length, 2)
  })

  for (const mode of ['401', 'empty401']) {
    await run(`${mode} clears identity once without automatic refresh or order retry`, {}, async f => {
      await f.login()
      const beforeRefresh = f.state.refreshCalls
      f.state.orderMode = mode
      const request = f.page.waitForRequest('**/api/orders/me')
      // A fast 401 may remove the CommercePanel before openOrders can inspect its heading.
      await f.page.getByRole('button', { name: '打开侧边栏' }).click()
      await f.page.getByRole('button', { name: '订单信息', exact: true }).click()
      await request
      await f.page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
      await f.settle()
      assert.equal(await f.page.getByRole('button', { name: '打开侧边栏' }).isDisabled(), true)
      assert.equal(await f.page.locator('.commerce-panel').count(), 0)
      assert.equal(f.state.refreshCalls, beforeRefresh)
      assert.equal(f.state.requests.length, 1)
    })
  }

  for (const mode of ['503', 'network']) {
    await run(`${mode} keeps identity and retries My Orders only on explicit reload`, {}, async f => {
      await f.login()
      f.state.orderMode = mode
      const beforeRefresh = f.state.refreshCalls
      await f.openOrders()
      await f.ordersPage.getByText('订单加载失败', { exact: true }).waitFor()
      assert.equal(await f.page.getByRole('dialog', { name: '登录 SmartMall' }).isVisible(), false)
      assert.equal(f.state.requests.length, 1)
      f.state.orderMode = 'success'
      await f.ordersPage.getByRole('button', { name: '重新加载', exact: true }).click()
      await f.ordersPage.getByText('#7007', { exact: true }).waitFor()
      assert.equal(f.state.requests.length, 2)
      assert.equal(f.state.refreshCalls, beforeRefresh)
    })
  }

  await run('logout cancels in-flight order request and the next account sees only its own orders', {}, async f => {
    await f.login()
    f.state.orderMode = 'delayed'
    await f.openOrders()
    await f.page.waitForFunction(() => window.__orderFetches.length === 1)
    await waitForFixture(() => f.state.pendingOrders.length === 1)
    await f.logout()
    await f.page.waitForFunction(() => window.__orderFetches[0].aborted)
    await f.login('bob')
    f.state.orderMode = 'success'
    await f.openOrders()
    await f.ordersPage.getByText('#8008', { exact: true }).waitFor()
    await f.state.pendingOrders[0].complete()
    await f.settle()
    assert.equal(await f.ordersPage.getByText('#7007', { exact: true }).count(), 0)
    assert.deepEqual(f.state.requests.map(request => request.id), [7, 8])
  })

  for (const lateStatus of [200, 401]) {
    await run(`late old-account ${lateStatus} cannot overwrite or sign out the new account`, {}, async f => {
      await f.login()
      await f.page.evaluate(() => { window.__ignoreOrderAbort = true })
      f.state.orderMode = 'delayed'
      await f.openOrders()
      await f.page.waitForFunction(() => window.__orderFetches.length === 1)
      await waitForFixture(() => f.state.pendingOrders.length === 1)
      await f.logout()
      await f.login('bob')
      f.state.orderMode = 'success'
      await f.openOrders()
      await f.ordersPage.getByText('#8008', { exact: true }).waitFor()
      await f.state.pendingOrders[0].complete(lateStatus)
      await f.page.waitForFunction(() => window.__orderFetches[0].settled)
      await f.settle()
      assert.equal(await f.ordersPage.getByText('#7007', { exact: true }).count(), 0)
      assert.equal(await f.ordersPage.getByText('#8008', { exact: true }).isVisible(), true)
      assert.equal(await f.page.getByRole('dialog', { name: '登录 SmartMall' }).isVisible(), false)
    })
  }

  await run('Access expiry cancels outstanding orders and leaves no business UI', { expiresIn: 2 }, async f => {
    await f.login()
    f.state.orderMode = 'delayed'
    await f.openOrders()
    await waitForFixture(() => f.state.pendingOrders.length === 1)
    await f.page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
    await f.page.waitForFunction(() => window.__orderFetches[0]?.aborted)
    await f.state.pendingOrders[0].complete()
    assert.equal(await f.page.locator('.commerce-panel').count(), 0)
    assert.equal(f.state.requests.length, 1)
  })
  console.log(`PASS all ${passed} My Orders browser groups`)
} finally {
  await browser.close()
}
