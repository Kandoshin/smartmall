// Focused reload regression: real Vue UI and browser Cookie transport, mocked auth server.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SMARTMALL_PLAYWRIGHT_MODULE || 'playwright')
const browser = await chromium.launch({ headless: true, channel: process.env.SMARTMALL_BROWSER || 'chrome' })
const baseUrl = process.env.SMARTMALL_BASE_URL || 'http://127.0.0.1:5173'
const context = await browser.newContext()
const page = await context.newPage()
const calls = { refresh: 0, me: 0, login: 0 }
const errors = []
page.on('pageerror', error => errors.push(error.message))
await context.addCookies([{ name: 'smartmall_refresh', value: 'test-refresh',
  domain: new URL(baseUrl).hostname, path: '/api/auth', httpOnly: true, sameSite: 'Strict',
  expires: Math.floor(Date.now() / 1000) + 3600 }])
const json = (route, data) => route.fulfill({ status: 200, contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: '操作成功', data }) })
await page.route('**/api/auth/csrf', route => route.fulfill({
  status: 200, contentType: 'application/json',
  headers: { 'Set-Cookie': 'smartmall_csrf=test-csrf; Path=/api/auth; HttpOnly; SameSite=Strict' },
  body: JSON.stringify({ code: 200, data: { headerName: 'X-XSRF-TOKEN', token: 'test-mask' } }),
}))
await page.route('**/api/auth/refresh', route => {
  calls.refresh++
  assert.equal(route.request().method(), 'POST')
  assert.equal(route.request().headers().authorization, undefined)
  assert.equal(route.request().headers()['x-xsrf-token'], 'test-mask')
  assert.ok(route.request().headers().cookie?.includes('smartmall_refresh=test-refresh'))
  return json(route, { accessToken: 'test-access', expiresIn: 900,
    user: { id: 42, username: 'refresh-response-name', email: null } })
})
await page.route('**/api/auth/me', route => {
  calls.me++
  assert.equal(route.request().headers().authorization, 'Bearer test-access')
  return json(route, { id: 42, username: 'verified-user', email: null })
})
await page.route('**/api/auth/login', route => { calls.login++; return route.abort() })

async function expectRestored() {
  const restored = await page.waitForFunction(() => {
    const menu = document.querySelector('button[aria-label="打开侧边栏"]')
    return menu && !menu.disabled
  }, undefined, { timeout: 2500 }).then(() => true, () => false)
  assert.ok(restored, `有刷新 Cookie 时应恢复身份；当前调用次数 ${JSON.stringify(calls)}`)
  assert.equal(await page.getByRole('dialog', { name: '登录 SmartMall' }).isVisible(), false)
}

try {
  await page.goto(baseUrl)
  await expectRestored()
  await page.reload()
  await expectRestored()
  assert.deepEqual(calls, { refresh: 2, me: 2, login: 0 })
  await page.getByRole('button', { name: '打开侧边栏' }).click()
  assert.equal(await page.locator('.profile-name').innerText(), 'verified-user')
  assert.equal((await context.cookies()).find(cookie => cookie.name === 'smartmall_refresh')?.value, 'test-refresh')
  assert.deepEqual(await page.evaluate(() => [Object.keys(localStorage), Object.keys(sessionStorage)]), [[], []])
  assert.deepEqual(errors, [])
  console.log('PASS initial page and reload restore identity from HttpOnly Cookie without login or Access Token storage')
} finally {
  await browser.close()
}
