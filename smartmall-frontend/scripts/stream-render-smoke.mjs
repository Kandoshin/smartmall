// Browser regression check for the visible SSE contract: the first delta must paint
// before the server sends the second one. It deliberately uses Vite's real dev proxy.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { createServer } from 'node:http'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.SMARTMALL_PLAYWRIGHT_MODULE || 'playwright')
const frontendRoot = fileURLToPath(new URL('..', import.meta.url))
const vitePort = 5188
const aiPort = 5189
const baseUrl = `http://127.0.0.1:${vitePort}`
const sse = (event, text) => `event:${event}\ndata:${JSON.stringify({ code: 200, message: '操作成功', data: { text } })}\n\n`

const aiServer = createServer((request, response) => {
  if (request.method !== 'POST' || request.url !== '/chat') {
    response.writeHead(404).end()
    return
  }
  response.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=UTF-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
  })
  response.write(sse('status', '正在思考…'))
  response.write(sse('delta', '第一段'))
  setTimeout(() => {
    response.write(sse('delta', '第二段\n\n- **查商品**：\n\n<script>window.__unsafeMarkdownRan = true</script>'))
    response.end(sse('done', ''))
  }, 1200)
})

function waitForServer(port) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + 15_000
    const timer = setInterval(async () => {
      try {
        const response = await fetch(`http://127.0.0.1:${port}`)
        if (response) {
          clearInterval(timer)
          resolve()
        }
      } catch {
        if (Date.now() >= deadline) {
          clearInterval(timer)
          reject(new Error('Vite did not start in time'))
        }
      }
    }, 100)
  })
}

await new Promise(resolve => aiServer.listen(aiPort, '127.0.0.1', resolve))
const vite = spawn('npm.cmd', ['run', 'dev', '--', '--config', 'scripts/stream-render-vite.config.mjs', '--host', '127.0.0.1', '--port', String(vitePort), '--strictPort'], {
  cwd: frontendRoot,
  stdio: 'ignore',
  windowsHide: true,
  shell: process.platform === 'win32',
})

let browser
try {
  await waitForServer(vitePort)
  browser = await chromium.launch({ headless: true, channel: process.env.SMARTMALL_BROWSER || 'chrome' })
  const context = await browser.newContext()
  const page = await context.newPage()
  page.setDefaultTimeout(5_000)
  const json = (route, data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: '操作成功', data }) })

  await context.route('**/api/auth/refresh', route => route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: '请先登录', data: null }) }))
  await context.route('**/api/auth/csrf', route => json(route, { headerName: 'X-XSRF-TOKEN', token: 'test-csrf' }))
  await context.route('**/api/auth/login', route => json(route, { accessToken: 'test-access', expiresIn: 900 }))
  await context.route('**/api/auth/me', route => json(route, { id: 1, username: '流式测试用户', email: null }))

  await page.goto(baseUrl)
  const proxyTiming = await page.evaluate(async () => {
    const startedAt = performance.now()
    const response = await fetch('/api/chat', { method: 'POST', body: '{}' })
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let received = ''
    let firstDeltaAt = null
    let secondDeltaAt = null
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      received += decoder.decode(value, { stream: true })
      if (firstDeltaAt === null && received.includes('第一段')) firstDeltaAt = performance.now() - startedAt
      if (secondDeltaAt === null && received.includes('第二段')) secondDeltaAt = performance.now() - startedAt
    }
    return { firstDeltaAt, secondDeltaAt }
  })
  assert.ok(proxyTiming.firstDeltaAt !== null && proxyTiming.firstDeltaAt < 900,
    `Vite proxy buffered the first SSE delta: ${JSON.stringify(proxyTiming)}`)
  assert.ok(proxyTiming.secondDeltaAt !== null && proxyTiming.secondDeltaAt >= 1_000,
    `the delayed second SSE delta was not observed separately: ${JSON.stringify(proxyTiming)}`)
  await page.getByLabel('用户名').fill('alice')
  await page.getByLabel('密码').fill('password')
  await page.getByRole('button', { name: '登录' }).click()
  const input = page.getByRole('textbox', { name: '发送消息', exact: true })
  await page.evaluate(() => {
    window.__streamDomTimeline = []
    const startedAt = performance.now()
    new MutationObserver(() => {
      const text = document.querySelector('.conversation')?.textContent?.replace(/\s+/g, ' ').trim() || ''
      window.__streamDomTimeline.push({ at: performance.now() - startedAt, text })
    }).observe(document.querySelector('.chat-main'), { childList: true, subtree: true, characterData: true })
  })
  await input.fill('测试流式渲染')
  await input.press('Enter')

  try {
    await page.getByText('第一段', { exact: true }).waitFor()
  } catch (error) {
    console.error(`stream DOM at timeout: ${await page.locator('.conversation').innerText()}`)
    console.error(`stream DOM timeline: ${JSON.stringify(await page.evaluate(() => window.__streamDomTimeline))}`)
    throw error
  }
  assert.equal(await page.getByText('第二段', { exact: true }).count(), 0,
    '第一段已经到达页面时，第二段不应提前出现')
  await page.getByText('第一段第二段', { exact: false }).waitFor()
  await page.locator('.assistant-message li strong', { hasText: '查商品' }).waitFor()
  assert.equal(await page.locator('.assistant-message script').count(), 0,
    'raw HTML from the model must not become executable DOM')
  assert.notEqual(await page.locator('.assistant-message').innerText().then(text => text.includes('<script>')), false,
    'disabled raw HTML should remain visible as text')
  assert.equal(await page.evaluate(() => window.__unsafeMarkdownRan), undefined,
    'raw model HTML must never execute')
  console.log('PASS SSE deltas render progressively as safe Markdown')
} finally {
  await browser?.close()
  await new Promise(resolve => aiServer.close(resolve))
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/pid', String(vite.pid), '/t', '/f'], { stdio: 'ignore' })
  } else {
    vite.kill()
  }
}
