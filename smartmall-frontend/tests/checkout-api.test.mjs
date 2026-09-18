import { afterEach, mock, test } from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, createOrder, getProducts, login, refreshSession, logoutSession, cancelOrder } from '../src/api.ts'

afterEach(() => mock.restoreAll())

const items = [{ productId: 2, quantity: 3 }]
const order = { id: 107, totalAmount: 299.9, status: 'NORMAL' }
const ok = () => Response.json({ code: 200, message: '操作成功', data: order })

test('checkout explicitly sends Access Bearer and items-only JSON without cookies or identity in URL/body', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    assert.equal(url, '/api/orders')
    assert.equal(options.method, 'POST')
    assert.equal(options.credentials, 'omit')
    assert.equal(options.cache, 'no-store')
    assert.deepEqual(options.headers, {
      'Content-Type': 'application/json', Authorization: 'Bearer checkout-access',
    })
    assert.deepEqual(JSON.parse(options.body), { items })
    assert.equal(options.body.includes('checkout-access'), false)
    assert.ok(options.signal instanceof AbortSignal)
    return ok()
  })
  assert.deepEqual(await createOrder('checkout-access', items), order)
  assert.deepEqual(calls, ['/api/orders'])
})

test('checkout changes the Bearer token per call while leaving the items-only body unchanged', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (_url, options) => {
    assert.deepEqual(JSON.parse(options.body), { items })
    calls.push(options.headers.Authorization)
    return ok()
  })
  await createOrder('alice-access', items)
  await createOrder('bob-access', items)
  assert.deepEqual(calls, ['Bearer alice-access', 'Bearer bob-access'])
})

for (const status of [400, 401, 403, 500]) {
  test(`checkout HTTP ${status} rejects without automatically refreshing or replaying the POST`, async () => {
    const calls = []
    mock.method(globalThis, 'fetch', async (url) => {
      calls.push(url)
      return Response.json({ code: status, message: '订单请求被拒绝', data: null }, { status })
    })
    await assert.rejects(createOrder('checkout-access', items), error =>
      error instanceof ApiError && error.status === status && error.message === '订单请求被拒绝')
    assert.deepEqual(calls, ['/api/orders'])
  })
}

test('checkout empty 401 uses a readable authentication error without replay', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return new Response(null, { status: 401 })
  })
  await assert.rejects(createOrder('expired-access', items), error =>
    error instanceof ApiError && error.status === 401 && error.message.includes('重新登录'))
  assert.deepEqual(calls, ['/api/orders'])
})

test('checkout network failure never replays a possibly completed order', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    throw new TypeError('Failed to fetch')
  })
  await assert.rejects(createOrder('checkout-access', items), { status: 0 })
  assert.deepEqual(calls, ['/api/orders'])
})

test('checkout malformed proxy response reports failure without another POST', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return new Response('<html>Bad gateway</html>', { status: 502 })
  })
  await assert.rejects(createOrder('checkout-access', items), { status: 502 })
  assert.deepEqual(calls, ['/api/orders'])
})

test('already cancelled checkout does not send the POST', async () => {
  const controller = new AbortController()
  controller.abort()
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => { calls.push(url); return ok() })
  await assert.rejects(createOrder('checkout-access', items, controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, [])
})

test('checkout combines caller cancellation with timeout without converting it to server failure', async () => {
  const controller = new AbortController()
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    assert.equal(options.signal.aborted, false)
    controller.abort()
    assert.equal(options.signal.aborted, true)
    throw options.signal.reason
  })
  await assert.rejects(createOrder('checkout-access', items, controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, ['/api/orders'])
})

test('cancelled checkout ignores even a successful response already being parsed', async () => {
  const controller = new AbortController()
  const response = ok()
  mock.method(response, 'json', async () => {
    controller.abort()
    return { code: 200, data: order }
  })
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => { calls.push(url); return response })
  await assert.rejects(createOrder('checkout-access', items, controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, ['/api/orders'])
})

test('checkout and cancellation receive explicit Bearers without leaking them into public or auth requests', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    const headers = new Headers(options.headers)
    if (url === '/api/orders') {
      assert.equal(headers.get('Authorization'), 'Bearer private-checkout-access')
      return ok()
    }
    if (url === '/api/orders/107/cancel') {
      assert.equal(options.method, 'PATCH')
      assert.equal(options.credentials, 'omit')
      assert.equal(options.cache, 'no-store')
      assert.equal(headers.get('Authorization'), 'Bearer private-cancel-access')
      assert.equal(options.body, undefined)
      return Response.json({ code: 200, message: '操作成功', data: { ...order, status: 'CANCELLED' } })
    }
    assert.equal(headers.get('Authorization'), null)
    assert.equal(String(options.body ?? '').includes('private-checkout-access'), false)
    if (url === '/api/auth/csrf') return Response.json({
      code: 200, data: { headerName: 'X-XSRF-TOKEN', token: 'test-csrf' },
    })
    return ok()
  })
  await createOrder('private-checkout-access', items)
  await getProducts({ name: '', status: '1', page: 1, size: 8 })
  await login({ username: 'alice', password: 'test-password' })
  await refreshSession()
  await logoutSession()
  await cancelOrder('private-cancel-access', 107)
  assert.deepEqual(calls, [
    '/api/orders', '/api/products?page=1&size=8&status=1',
    '/api/auth/csrf', '/api/auth/login', '/api/auth/csrf', '/api/auth/refresh',
    '/api/auth/csrf', '/api/auth/logout', '/api/orders/107/cancel',
  ])
})

test('cancellation preserves a rejected response and never retries the PATCH', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return Response.json({ code: 404, message: '订单不存在：107', data: null }, { status: 404 })
  })
  await assert.rejects(cancelOrder('cancel-access', 107), error =>
    error instanceof ApiError && error.status === 404 && error.message === '订单不存在：107')
  assert.deepEqual(calls, ['/api/orders/107/cancel'])
})
