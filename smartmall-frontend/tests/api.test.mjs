import { afterEach, mock, test } from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, getCurrentUser, getMyOrders, getOrderDetail, getProducts, login, refreshSession, logoutSession, streamChatMessage } from '../src/api.ts'

afterEach(() => mock.restoreAll())

const csrfResponse = () => Response.json({ code: 200, data: { headerName: 'X-XSRF-TOKEN', token: 'test-csrf' } })
const sseEvent = (name, payload) => `event:${name}\ndata:${JSON.stringify(payload)}\n\n`
const sseResponse = (...chunks) => {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

test('logout POST uses browser cookies and CSRF without body or Bearer, returning null', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.body, undefined)
    if (url === '/api/auth/csrf') return csrfResponse()
    assert.equal(url, '/api/auth/logout')
    assert.equal(options.method, 'POST')
    assert.deepEqual(options.headers, { 'X-XSRF-TOKEN': 'test-csrf' })
    return Response.json({ code: 200, message: '操作成功', data: null })
  })
  assert.equal(await logoutSession(), null)
  assert.deepEqual(calls, ['/api/auth/csrf', '/api/auth/logout'])
})

test('logout reports failures without claiming success or automatically retrying', async () => {
  for (const status of [401, 403, 500]) {
    const calls = []
    mock.method(globalThis, 'fetch', async (url) => {
      calls.push(url)
      return url === '/api/auth/csrf' ? csrfResponse()
        : Response.json({ code: status, message: '退出失败', data: null }, { status })
    })
    await assert.rejects(logoutSession(), { status })
    assert.deepEqual(calls, ['/api/auth/csrf', '/api/auth/logout'])
    mock.restoreAll()
  }
})

test('logout does not POST when CSRF bootstrap fails or is malformed', async () => {
  for (const response of [new Response(null, { status: 502 }),
    Response.json({ code: 200, data: { headerName: 'Authorization', token: 'wrong' } })]) {
    const calls = []
    mock.method(globalThis, 'fetch', async (url) => { calls.push(url); return response })
    await assert.rejects(logoutSession(), ApiError)
    assert.deepEqual(calls, ['/api/auth/csrf'])
    mock.restoreAll()
  }
})

test('logout cancellation during CSRF bootstrap prevents POST', async () => {
  const controller = new AbortController()
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    controller.abort()
    return csrfResponse()
  })
  await assert.rejects(logoutSession(controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, ['/api/auth/csrf'])
})

test('refresh requests browser cookies and CSRF without passwords, body or Bearer', async () => {
  const result = { accessToken: 'new-access', expiresIn: 900, user: { id: 42, username: 'alice' } }
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    assert.equal(options.credentials, 'same-origin')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.body, undefined)
    if (url === '/api/auth/csrf') return csrfResponse()
    assert.equal(url, '/api/auth/refresh')
    assert.equal(options.method, 'POST')
    assert.deepEqual(options.headers, { 'X-XSRF-TOKEN': 'test-csrf' })
    return Response.json({ code: 200, data: result })
  })
  assert.deepEqual(await refreshSession(), result)
  assert.deepEqual(calls, ['/api/auth/csrf', '/api/auth/refresh'])
})

test('refresh reports rejected or unavailable service without retries', async () => {
  for (const status of [401, 403, 500]) {
    const calls = []
    mock.method(globalThis, 'fetch', async (url) => {
      calls.push(url)
      return url === '/api/auth/csrf' ? csrfResponse()
        : Response.json({ code: status, message: '请求失败', data: null }, { status })
    })
    await assert.rejects(refreshSession(), { status })
    assert.deepEqual(calls, ['/api/auth/csrf', '/api/auth/refresh'])
    mock.restoreAll()
  }
})

test('refresh does not POST if CSRF bootstrap fails or returns invalid data', async () => {
  for (const response of [new Response(null, { status: 502 }),
    Response.json({ code: 200, data: { headerName: 'Authorization', token: 'wrong' } })]) {
    const calls = []
    mock.method(globalThis, 'fetch', async (url) => { calls.push(url); return response })
    await assert.rejects(refreshSession(), ApiError)
    assert.deepEqual(calls, ['/api/auth/csrf'])
    mock.restoreAll()
  }
})

test('cancelling refresh during CSRF bootstrap prevents the POST', async () => {
  const controller = new AbortController()
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    controller.abort()
    return csrfResponse()
  })
  await assert.rejects(refreshSession(controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, ['/api/auth/csrf'])
})

test('login sends credentials as JSON and unwraps Result.data', async () => {
  const result = { accessToken: 'test-token', expiresIn: 900, user: { id: 42, username: 'alice', email: null } }
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    assert.equal(options.credentials, 'same-origin')
    if (url === '/api/auth/csrf') {
      assert.equal(options.cache, 'no-store')
      assert.equal(options.body, undefined)
      assert.equal(options.headers, undefined)
      return csrfResponse()
    }
    assert.equal(url, '/api/auth/login')
    assert.equal(options.method, 'POST')
    assert.equal(options.headers['X-XSRF-TOKEN'], 'test-csrf')
    assert.deepEqual(JSON.parse(options.body), { username: 'alice', password: ' a password ' })
    assert.equal(options.headers.Authorization, undefined)
    return Response.json({ code: 200, message: '操作成功', data: result })
  })
  assert.deepEqual(await login({ username: 'alice', password: ' a password ' }), result)
  assert.deepEqual(calls, ['/api/auth/csrf', '/api/auth/login'])
})

test('current-user Bearer credentials do not spill into product requests', async () => {
  const user = { id: 42, username: 'alice', email: null }
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, options })
    return Response.json({ code: 200, message: '操作成功', data: user })
  })
  assert.deepEqual(await getCurrentUser('test-token'), user)
  await getProducts({ name: '', status: '1', page: 1, size: 8 })
  assert.equal(calls[0].url, '/api/auth/me')
  assert.equal(calls[0].options.headers.Authorization, 'Bearer test-token')
  assert.equal(calls[1].options.headers, undefined)
})

test('my orders uses only the supplied Bearer on GET /api/orders/me and unwraps nonempty data', async () => {
  const orders = [{ id: 101, totalAmount: 199, status: 'NORMAL', createdAt: '2026-09-11T12:00:00' }]
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    assert.equal(url, '/api/orders/me')
    assert.equal(options.method ?? 'GET', 'GET')
    assert.equal(options.body, undefined)
    assert.equal(options.cache, 'no-store')
    assert.equal(options.credentials, 'omit')
    assert.deepEqual([...new Headers(options.headers)], [['authorization', 'Bearer orders-access']])
    return Response.json({ code: 200, message: '操作成功', data: orders })
  })

  assert.deepEqual(await getMyOrders('orders-access'), orders)
  assert.deepEqual(calls, ['/api/orders/me'])
})

test('my orders returns an empty array without treating it as an error', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return Response.json({ code: 200, message: '操作成功', data: [] })
  })

  assert.deepEqual(await getMyOrders('orders-access'), [])
  assert.deepEqual(calls, ['/api/orders/me'])
})

test('order detail uses the path id and supplied Bearer without cookies', async () => {
  const detail = {
    id: 101, userId: 7, totalAmount: 199, status: 'NORMAL',
    createdAt: '2026-09-15T10:00:00',
    items: [{ productId: 2, productName: '机械键盘', unitPrice: 199, quantity: 1, subtotal: 199 }],
  }
  mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/orders/101')
    assert.equal(options.method ?? 'GET', 'GET')
    assert.equal(options.credentials, 'omit')
    assert.equal(options.cache, 'no-store')
    assert.deepEqual([...new Headers(options.headers)], [['authorization', 'Bearer detail-access']])
    return Response.json({ code: 200, message: '操作成功', data: detail })
  })

  assert.deepEqual(await getOrderDetail('detail-access', 101), detail)
})

test('my orders reads the token argument on each call rather than retaining an earlier identity', async () => {
  const authorizations = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/orders/me')
    authorizations.push(new Headers(options.headers).get('Authorization'))
    return Response.json({ code: 200, data: [] })
  })

  await getMyOrders('first-user-access')
  await getMyOrders('second-user-access')
  assert.deepEqual(authorizations, ['Bearer first-user-access', 'Bearer second-user-access'])
})

test('my orders preserves a JSON 401 message and does not refresh or retry', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return Response.json({ code: 401, message: '身份已失效', data: null }, { status: 401 })
  })

  await assert.rejects(getMyOrders('expired-access'),
    (error) => error instanceof ApiError && error.status === 401 && error.message === '身份已失效')
  assert.deepEqual(calls, ['/api/orders/me'])
})

test('my orders translates an empty 401 into ApiError without refresh or retry', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return new Response(null, { status: 401 })
  })

  await assert.rejects(getMyOrders('expired-access'),
    (error) => error instanceof ApiError && error.status === 401 && error.message.includes('重新登录'))
  assert.deepEqual(calls, ['/api/orders/me'])
})

for (const status of [403, 500]) {
  test(`my orders reports HTTP ${status} without refreshing or retrying`, async () => {
    const calls = []
    mock.method(globalThis, 'fetch', async (url) => {
      calls.push(url)
      return Response.json({ code: status, message: '订单暂不可用', data: null }, { status })
    })

    await assert.rejects(getMyOrders('orders-access'),
      (error) => error instanceof ApiError && error.status === status && error.message === '订单暂不可用')
    assert.deepEqual(calls, ['/api/orders/me'])
  })
}

test('my orders translates an HTML 502 without leaking a parsing error or retrying', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return new Response('<html>Bad gateway</html>', { status: 502 })
  })

  await assert.rejects(getMyOrders('orders-access'),
    (error) => error instanceof ApiError && error.status === 502 && error.message.includes('后端已启动'))
  assert.deepEqual(calls, ['/api/orders/me'])
})

test('my orders reports a network failure without automatic refresh or retry', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    throw new TypeError('Failed to fetch')
  })

  await assert.rejects(getMyOrders('orders-access'),
    (error) => error instanceof ApiError && error.status === 0 && error.message.includes('重试'))
  assert.deepEqual(calls, ['/api/orders/me'])
})

test('my orders forwards cancellation and preserves AbortError without retry', async () => {
  const controller = new AbortController()
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    assert.equal(options.signal.aborted, false)
    controller.abort()
    assert.equal(options.signal.aborted, true)
    throw options.signal.reason
  })

  await assert.rejects(getMyOrders('orders-access', controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, ['/api/orders/me'])
})

test('my orders does not send a request when its caller has already cancelled', async () => {
  const controller = new AbortController()
  controller.abort()
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return Response.json({ code: 200, data: [] })
  })

  await assert.rejects(getMyOrders('orders-access', controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, [])
})

test('my orders preserves cancellation while reading the response body', async () => {
  const controller = new AbortController()
  const calls = []
  const response = Response.json({ code: 200, data: [] })
  mock.method(response, 'json', async () => {
    controller.abort()
    throw controller.signal.reason
  })
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return response
  })

  await assert.rejects(getMyOrders('orders-access', controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, ['/api/orders/me'])
})

test('my-orders Bearer does not leak into products, login, refresh, logout or CSRF bootstrap', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push(url)
    const headers = new Headers(options.headers)
    if (url === '/api/orders/me') {
      assert.equal(headers.get('Authorization'), 'Bearer private-order-access')
      return Response.json({ code: 200, data: [] })
    }
    assert.equal(headers.get('Authorization'), null)
    assert.equal(String(options.body ?? '').includes('private-order-access'), false)
    if (url === '/api/auth/csrf') return csrfResponse()
    if (url.startsWith('/api/products?')) {
      return Response.json({ code: 200, data: { records: [], total: 0, current: 1, size: 8 } })
    }
    assert.equal(options.credentials, 'same-origin')
    assert.equal(headers.get('X-XSRF-TOKEN'), 'test-csrf')
    return Response.json({ code: 200, data: null })
  })

  await getMyOrders('private-order-access')
  await getProducts({ name: '', status: '1', page: 1, size: 8 })
  await login({ username: 'alice', password: 'test-password' })
  await refreshSession()
  await logoutSession()
  assert.deepEqual(calls, [
    '/api/orders/me', '/api/products?page=1&size=8&status=1',
    '/api/auth/csrf', '/api/auth/login',
    '/api/auth/csrf', '/api/auth/refresh',
    '/api/auth/csrf', '/api/auth/logout',
  ])
})

test('401 preserves the server message and status', async () => {
  mock.method(globalThis, 'fetch', async (url) => url === '/api/auth/csrf' ? csrfResponse() : Response.json(
    { code: 401, message: '用户名或密码错误', data: null }, { status: 401 },
  ))
  await assert.rejects(login({ username: 'alice', password: 'wrong' }),
    (error) => error instanceof ApiError && error.status === 401 && error.message === '用户名或密码错误')
})

test('failed CSRF bootstrap never sends credentials', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return new Response(null, { status: 502 })
  })
  await assert.rejects(login({ username: 'alice', password: 'test-password' }), { status: 502 })
  assert.deepEqual(calls, ['/api/auth/csrf'])
})

test('invalid CSRF data or unexpected header name never sends credentials', async () => {
  for (const data of [null, {}, { headerName: 'Authorization', token: 'bad' },
    { headerName: 'X-XSRF-TOKEN', token: '' }, { headerName: 'X-XSRF-TOKEN', token: 42 }]) {
    const calls = []
    mock.method(globalThis, 'fetch', async (url) => {
      calls.push(url)
      return Response.json({ code: 200, data })
    })
    await assert.rejects(login({ username: 'alice', password: 'test-password' }),
      (error) => error instanceof ApiError && error.message.includes('初始化登录校验'))
    assert.deepEqual(calls, ['/api/auth/csrf'])
    mock.restoreAll()
  }
})

test('abort between CSRF bootstrap and login prevents credential submission', async () => {
  const controller = new AbortController()
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    controller.abort()
    return csrfResponse()
  })
  await assert.rejects(login({ username: 'alice', password: 'test-password' }, controller.signal), { name: 'AbortError' })
  assert.deepEqual(calls, ['/api/auth/csrf'])
})

test('CSRF rejection is reported without automatically retrying login', async () => {
  const calls = []
  mock.method(globalThis, 'fetch', async (url) => {
    calls.push(url)
    return url === '/api/auth/csrf' ? csrfResponse()
      : Response.json({ code: 403, message: '无权访问该资源', data: null }, { status: 403 })
  })
  await assert.rejects(login({ username: 'alice', password: 'test-password' }), { status: 403 })
  assert.deepEqual(calls, ['/api/auth/csrf', '/api/auth/login'])
})

test('an empty 401 still produces a usable login-expired message', async () => {
  mock.method(globalThis, 'fetch', async () => new Response(null, { status: 401 }))
  await assert.rejects(getCurrentUser('expired'),
    (error) => error instanceof ApiError && error.status === 401 && error.message.includes('重新登录'))
})

test('proxy HTML errors do not leak a JSON parsing exception', async () => {
  mock.method(globalThis, 'fetch', async () => new Response('<html>Bad gateway</html>', { status: 502 }))
  await assert.rejects(getCurrentUser('token'),
    (error) => error instanceof ApiError && error.status === 502 && error.message.includes('后端已启动'))
})

test('network failures have a user-facing retry message', async () => {
  mock.method(globalThis, 'fetch', async () => { throw new TypeError('Failed to fetch') })
  await assert.rejects(getCurrentUser('token'),
    (error) => error instanceof ApiError && error.status === 0 && error.message.includes('重试'))
})

test('cancellation is not converted into a server failure', async () => {
  const controller = new AbortController()
  controller.abort()
  mock.method(globalThis, 'fetch', async (_url, options) => { throw options.signal.reason })
  await assert.rejects(getCurrentUser('token', controller.signal), { name: 'AbortError' })
})

test('chat streams status and split text deltas with only the supplied Access Bearer', async () => {
  mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url, '/api/chat')
    assert.equal(options.method, 'POST')
    assert.equal(options.credentials, 'omit')
    assert.equal(options.headers.Accept, 'text/event-stream')
    assert.equal(options.headers.Authorization, 'Bearer chat-access')
    assert.deepEqual(JSON.parse(options.body), { message: '推荐一把键盘' })
    const status = sseEvent('status', { code: 200, message: '操作成功', data: { text: '正在查询商品…' } })
    const delta1 = sseEvent('delta', { code: 200, message: '操作成功', data: { text: '推荐' } })
    const delta2 = sseEvent('delta', { code: 200, message: '操作成功', data: { text: '结果' } })
    const done = sseEvent('done', { code: 200, message: '操作成功', data: { text: '' } })
    return sseResponse(status.slice(0, 17), status.slice(17) + delta1 + delta2.slice(0, 9), delta2.slice(9) + done)
  })

  const statuses = []
  const deltas = []
  await streamChatMessage('chat-access', '推荐一把键盘', {
    onStatus: text => statuses.push(text),
    onDelta: text => deltas.push(text),
  })
  assert.deepEqual(statuses, ['正在查询商品…'])
  assert.deepEqual(deltas, ['推荐', '结果'])
})

test('chat preserves an HTTP authentication failure without retrying', async () => {
  let calls = 0
  mock.method(globalThis, 'fetch', async () => {
    calls += 1
    return Response.json({ code: 401, message: '请先登录或重新登录', data: null }, { status: 401 })
  })
  await assert.rejects(streamChatMessage('chat-access', '你好', { onDelta() {} }),
    (error) => error instanceof ApiError && error.status === 401)
  assert.equal(calls, 1)
})

test('chat turns an SSE error event into ApiError while preserving earlier text', async () => {
  mock.method(globalThis, 'fetch', async () => sseResponse(
    sseEvent('delta', { code: 200, message: '操作成功', data: { text: '部分回答' } }),
    sseEvent('error', { code: 503, message: 'AI 服务暂时不可用', data: null }),
  ))
  const deltas = []
  await assert.rejects(streamChatMessage('chat-access', '你好', { onDelta: text => deltas.push(text) }),
    (error) => error instanceof ApiError && error.status === 503 && error.message.includes('暂时不可用'))
  assert.deepEqual(deltas, ['部分回答'])
})

test('chat rejects a successful HTTP stream that closes without done', async () => {
  mock.method(globalThis, 'fetch', async () => sseResponse(
    sseEvent('delta', { code: 200, message: '操作成功', data: { text: '未完成' } }),
  ))
  await assert.rejects(streamChatMessage('chat-access', '你好', { onDelta() {} }),
    (error) => error instanceof ApiError && error.status === 0 && error.message.includes('意外中断'))
})
