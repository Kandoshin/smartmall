import { afterEach, mock, test } from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, getCurrentUser, getProducts, login } from '../src/api.ts'

afterEach(() => mock.restoreAll())

const csrfResponse = () => Response.json({ code: 200, data: { headerName: 'X-XSRF-TOKEN', token: 'test-csrf' } })

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

test('only current-user request carries the supplied token', async () => {
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
