// Real Vue UI and fetch transport; every API route is mocked. No real order/account/database.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdir } from 'node:fs/promises'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SMARTMALL_PLAYWRIGHT_MODULE || 'playwright')
const browser = await chromium.launch({ headless: true, channel: process.env.SMARTMALL_BROWSER || 'chrome' })
const baseUrl = process.env.SMARTMALL_BASE_URL || 'http://127.0.0.1:5173'
const users = {
  7: { id: 7, username: '下单用户甲', email: null },
  8: { id: 8, username: '下单用户乙', email: null },
}
const orderFor = id => ({ id: id * 1000 + id, totalAmount: 299.9, status: 'PENDING_PAYMENT' })
const json = (route, status, data, message = '操作成功', headers = {}) => route.fulfill({
  status, contentType: 'application/json', headers, body: JSON.stringify({ code: status, message, data }),
})
let passed = 0

async function waitForFixture(predicate) {
  const deadline = Date.now() + 7000
  while (!predicate()) {
    assert.ok(Date.now() < deadline, 'Timed out waiting for mocked API handler')
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

async function fixture({ expiresIn = 900 } = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  const page = await context.newPage()
  page.setDefaultTimeout(7000)
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await context.addInitScript(() => {
    window.__checkoutFetches = []
    window.__ignoreCheckoutAbort = false
    const nativeFetch = window.fetch.bind(window)
    window.fetch = (input, init = {}) => {
      const url = new URL(typeof input === 'string' ? input : input.url, location.href)
      if (url.pathname !== '/api/orders' || init.method !== 'POST') return nativeFetch(input, init)
      const entry = {
        credentials: init.credentials, cache: init.cache,
        authorization: new Headers(init.headers).get('Authorization'),
        url: url.href, aborted: false, settled: false,
      }
      window.__checkoutFetches.push(entry)
      init.signal?.addEventListener('abort', () => { entry.aborted = true }, { once: true })
      const options = window.__ignoreCheckoutAbort ? { ...init, signal: undefined } : init
      return nativeFetch(input, options).then(response => {
        entry.settled = true
        return response
      }, error => {
        entry.settled = true
        throw error
      })
    }
  })
  await context.addCookies([{ name: 'root_cookie', value: 'never-send-to-checkout',
    domain: new URL(baseUrl).hostname, path: '/', sameSite: 'Strict' }])
  const state = {
    mode: 'success', expiresIn, issued: 0, tokens: new Map(), lastToken: '',
    posts: [], pending: [], refreshCalls: 0, loginCalls: 0, logoutCalls: 0,
    orderCalls: 0, unexpected: [],
  }
  function issue(id, prefix) {
    const token = `${prefix}-${id}-${++state.issued}`
    state.tokens.set(token, id)
    state.lastToken = token
    // /auth/me still confirms the session, but checkout identity comes from Bearer, not a body ID.
    // Deliberately disagree with /auth/me so the unverified login user is never used as an identity field.
    return { accessToken: token, expiresIn: state.expiresIn, user: { id: 99, username: 'unverified', email: null } }
  }
  await context.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const headers = request.headers()
    if (url.pathname === '/api/auth/csrf') {
      assert.equal(headers.authorization, undefined)
      return json(route, 200, { headerName: 'X-XSRF-TOKEN', token: 'checkout-mask' })
    }
    if (url.pathname === '/api/auth/login') {
      state.loginCalls++
      assert.equal(headers.authorization, undefined)
      assert.equal(headers['x-xsrf-token'], 'checkout-mask')
      const id = request.postDataJSON().username === 'bob' ? 8 : 7
      return json(route, 200, issue(id, 'login'), '操作成功',
        { 'Set-Cookie': `smartmall_refresh=refresh-${id}; Path=/api/auth; HttpOnly; SameSite=Strict` })
    }
    if (url.pathname === '/api/auth/refresh') {
      state.refreshCalls++
      assert.equal(headers.authorization, undefined)
      assert.equal(headers['x-xsrf-token'], 'checkout-mask')
      const id = Number((headers.cookie || '').match(/smartmall_refresh=refresh-(7|8)/)?.[1])
      return id ? json(route, 200, issue(id, 'restored')) : json(route, 401, null, '登录已失效，请重新登录')
    }
    if (url.pathname === '/api/auth/me') {
      const id = state.tokens.get(headers.authorization?.replace('Bearer ', ''))
      assert.ok(id)
      return json(route, 200, users[id])
    }
    if (url.pathname === '/api/auth/logout') {
      state.logoutCalls++
      assert.equal(headers.authorization, undefined)
      return json(route, 200, null, '操作成功',
        { 'Set-Cookie': 'smartmall_refresh=; Max-Age=0; Path=/api/auth; HttpOnly; SameSite=Strict' })
    }
    if (url.pathname === '/api/products') {
      assert.equal(headers.authorization, undefined)
      return json(route, 200, { current: 1, size: 8, total: 1, pages: 1, records: [
        { id: 1, name: '机械键盘', description: '模拟下单商品', price: 299.9, stock: 10, status: 1 },
      ] })
    }
    if (url.pathname === '/api/orders/me') {
      state.orderCalls++
      const id = state.tokens.get(headers.authorization?.replace('Bearer ', ''))
      assert.ok(id)
      return json(route, 200, [orderFor(id)])
    }
    if (url.pathname === '/api/orders' && request.method() === 'POST') {
      assert.equal(url.search, '')
      assert.equal(headers.cookie, undefined, 'Checkout must omit even root-path browser Cookies')
      assert.equal(headers['x-xsrf-token'], undefined)
      const token = headers.authorization?.replace('Bearer ', '')
      const id = state.tokens.get(token)
      assert.ok(id, 'Checkout must carry this session’s Access Token')
      const body = request.postDataJSON()
      assert.deepEqual(body, { items: [{ productId: 1, quantity: 1 }] })
      state.posts.push({ id, token, body })
      if (state.mode === 'delayed') {
        return new Promise(resolve => state.pending.push({ complete: async (status = 200) => {
          try { await json(route, status, status === 200 ? orderFor(id) : null, '旧下单请求响应') }
          catch { /* A transport-canceled request may no longer accept its response. */ }
          finally { resolve() }
        } }))
      }
      if (state.mode === 'network') return route.abort('failed')
      if (state.mode === 'malformed') return json(route, 200, { id: 'bad' })
      if (state.mode === 'empty401') return route.fulfill({ status: 401, body: '' })
      if (state.mode !== 'success') return json(route, Number(state.mode), null, `下单被拒绝 ${state.mode}`)
      return json(route, 200, orderFor(id))
    }
    state.unexpected.push(`${request.method()} ${url.pathname}`)
    return route.abort('blockedbyclient')
  })
  const mall = page.locator('#storefront-view')
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
  async function addCart() {
    await page.getByRole('button', { name: '逛商城', exact: true }).click()
    await mall.getByRole('heading', { name: '机械键盘' }).waitFor()
    assert.equal(await mall.getByLabel('下单用户 ID', { exact: true }).count(), 0)
    await mall.getByRole('button', { name: '加入购物车', exact: true }).click()
  }
  async function submit() { await mall.getByRole('button', { name: '提交订单', exact: true }).click() }
  async function logout() {
    await page.getByRole('button', { name: '打开侧边栏' }).click()
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
    for (const request of await page.evaluate(() => window.__checkoutFetches)) {
      assert.equal(request.credentials, 'omit')
      assert.equal(request.cache, 'no-store')
      assert.ok(request.authorization?.startsWith('Bearer '))
      assert.equal(new URL(request.url).search, '')
    }
    assert.deepEqual(await page.evaluate(() => [Object.keys(localStorage), Object.keys(sessionStorage)]), [[], []])
    for (const cookie of await context.cookies()) assert.ok(!state.tokens.has(cookie.value))
  }
  return { context, page, state, mall, login, loggedIn, addCart, submit, logout, settle, checkClean }
}

async function run(name, options, body) {
  const f = await fixture(options)
  try {
    await f.page.goto(baseUrl)
    await body(f)
    await f.checkClean()
    passed++
    console.log(`PASS ${name}`)
  } catch (error) {
    await mkdir('artifacts', { recursive: true })
    await f.page.screenshot({ path: 'artifacts/checkout-auth-failure.png', fullPage: true }).catch(() => {})
    throw error
  } finally {
    for (const pending of f.state.pending) await pending.complete()
    await f.context.close()
  }
}

try {
  await run('successful checkout sends items only and identifies the buyer through memory Bearer, then clears cart and opens My Orders', {}, async f => {
    await f.login()
    await f.addCart()
    await mkdir('artifacts', { recursive: true })
    await f.page.screenshot({ path: 'artifacts/checkout-auth-desktop.png', fullPage: true })
    await f.page.setViewportSize({ width: 320, height: 844 })
    assert.equal(await f.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    await f.mall.getByRole('button', { name: '提交订单', exact: true }).scrollIntoViewIfNeeded()
    assert.equal(await f.mall.getByRole('button', { name: '提交订单', exact: true }).evaluate(button => {
      const box = button.getBoundingClientRect()
      return button.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2))
    }), true)
    await f.page.screenshot({ path: 'artifacts/checkout-auth-mobile-320.png', fullPage: true })
    await f.submit()
    await f.mall.getByText('#7007', { exact: true }).waitFor()
    assert.equal(f.state.posts[0].id, 7)
    assert.equal(f.state.posts[0].token, f.state.lastToken)
    assert.equal(f.state.posts.length, 1)
    await f.mall.getByRole('button', { name: '全部商品', exact: true }).click()
    assert.equal(await f.mall.locator('.cart-item').count(), 0)
  })

  await run('reload restores a new memory token which checkout uses without repeating password login', {}, async f => {
    await f.login('bob')
    const oldToken = f.state.lastToken
    await f.page.reload()
    await f.loggedIn()
    await f.addCart()
    await f.submit()
    await f.mall.getByText('#8008', { exact: true }).waitFor()
    assert.notEqual(f.state.posts[0].token, oldToken)
    assert.ok(f.state.posts[0].token.startsWith('restored-8-'))
    assert.equal(f.state.loginCalls, 1)
  })

  for (const mode of ['401', 'empty401']) {
    await run(`${mode} clears local identity without refresh/logout/replaying POST`, {}, async f => {
      await f.login()
      await f.addCart()
      f.state.mode = mode
      const refreshBefore = f.state.refreshCalls
      await f.submit()
      await f.page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
      await f.settle()
      assert.equal(f.state.posts.length, 1)
      assert.equal(f.state.refreshCalls, refreshBefore)
      assert.equal(f.state.logoutCalls, 0)
      assert.equal(await f.page.locator('.commerce-panel').count(), 0)
      assert.ok((await f.context.cookies()).some(cookie => cookie.name === 'smartmall_refresh'))
    })
  }

  for (const mode of ['400', '403', '503', 'network', 'malformed']) {
    await run(`${mode} keeps identity/cart and never automatically repeats checkout`, {}, async f => {
      await f.login()
      await f.addCart()
      f.state.mode = mode
      const refreshBefore = f.state.refreshCalls
      await f.submit()
      const message = ['400', '403'].includes(mode) ? `下单被拒绝 ${mode}` : '未能确认是否下单成功'
      await f.page.getByRole('status').filter({ hasText: message }).waitFor()
      await f.settle()
      assert.equal(await f.page.getByRole('dialog', { name: '登录 SmartMall' }).isVisible(), false)
      assert.equal(await f.mall.locator('.cart-item').count(), 1)
      assert.equal(await f.mall.locator('.quantity-control > span').innerText(), '1')
      assert.equal(f.state.posts.length, 1)
      assert.equal(f.state.refreshCalls, refreshBefore)
      assert.equal(f.state.logoutCalls, 0)
      assert.equal(f.state.orderCalls, 0)
    })
  }

  await run('pending checkout guards synchronous duplicate clicks and cart mutation', {}, async f => {
    await f.login()
    await f.addCart()
    f.state.mode = 'delayed'
    await f.mall.getByRole('button', { name: '提交订单', exact: true }).evaluate(button => {
      button.click(); button.click()
    })
    await waitForFixture(() => f.state.pending.length === 1)
    assert.equal(await f.mall.getByRole('button', { name: '正在创建订单...', exact: true }).isDisabled(), true)
    for (const name of ['加入购物车', '+', '−', '移除']) {
      assert.equal(await f.mall.getByRole('button', { name, exact: true }).isDisabled(), true)
    }
    assert.equal(f.state.posts.length, 1)
    await f.state.pending[0].complete()
    await f.mall.getByText('#7007', { exact: true }).waitFor()
    assert.equal(f.state.posts.length, 1)
  })

  for (const lateStatus of [200, 401]) {
    await run(`logout cancels checkout; late old-account ${lateStatus} cannot alter new identity/cart/navigation`, {}, async f => {
      await f.login()
      await f.addCart()
      await f.page.evaluate(() => { window.__ignoreCheckoutAbort = true })
      f.state.mode = 'delayed'
      await f.submit()
      await waitForFixture(() => f.state.pending.length === 1)
      await f.logout()
      await f.page.waitForFunction(() => window.__checkoutFetches[0].aborted)
      await f.login('bob')
      await f.addCart()
      await f.state.pending[0].complete(lateStatus)
      await f.page.waitForFunction(() => window.__checkoutFetches[0].settled)
      await f.settle()
      assert.equal(await f.page.getByRole('dialog', { name: '登录 SmartMall' }).isVisible(), false)
      assert.equal(await f.mall.locator('.cart-item').count(), 1)
      assert.equal(await f.mall.getByRole('heading', { name: '机械键盘' }).isVisible(), true)
      assert.equal(await f.mall.getByText('#7007', { exact: true }).count(), 0)
      assert.equal(f.state.posts.length, 1)
      assert.equal(f.state.orderCalls, 0)
      assert.equal(f.state.logoutCalls, 1)
    })
  }

  await run('Access expiry aborts in-flight checkout and a late success cannot restore the old business UI', { expiresIn: 3 }, async f => {
    await f.login()
    await f.addCart()
    await f.page.evaluate(() => { window.__ignoreCheckoutAbort = true })
    f.state.mode = 'delayed'
    await f.submit()
    await waitForFixture(() => f.state.pending.length === 1)
    await f.page.getByRole('heading', { name: '登录 SmartMall' }).waitFor()
    await f.page.waitForFunction(() => window.__checkoutFetches[0].aborted)
    await f.state.pending[0].complete()
    await f.page.waitForFunction(() => window.__checkoutFetches[0].settled)
    await f.settle()
    assert.equal(await f.page.locator('.commerce-panel').count(), 0)
    assert.equal(f.state.posts.length, 1)
    assert.equal(f.state.orderCalls, 0)
    assert.equal(f.state.logoutCalls, 0)
  })
  console.log(`PASS all ${passed} checkout authentication browser groups`)
} finally {
  await browser.close()
}
