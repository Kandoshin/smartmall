# SmartMall User Service API

Base URL: `http://localhost:8080`

## 当前认证与访问规则

以下规则仅作用于用户服务（8080），不代表商品/订单服务已接入认证。

| 请求 | 当前访问条件 |
|---|---|
| `GET /auth/csrf` | 可匿名领取 CSRF 校验值，不产生登录身份 |
| `POST /auth/register` | 可匿名访问；必须通过 CSRF，再校验表单和业务规则 |
| `POST /auth/login` | 可匿名访问；必须通过 CSRF，再检查用户名、密码 |
| `GET /auth/me` | 必须携带有效 JWT |
| 其他用户服务接口，包括 `/users` CRUD | 暂时全部拒绝；管理员授权尚未实现 |

下方原有 `/users` CRUD 示例描述的是 Controller/Service 的业务契约，**当前不代表可以通过 HTTP 直接访问**。匿名 GET `/users` 返回 401；有效 JWT 访问仍返回 403。不要为恢复旧示例而开放整个用户列表或修改入口。

### 登录并查询当前用户

本地先按 README 使用 `local-http` profile 启动用户服务，再启动 Vite。浏览器或 Postman 建议统一使用代理地址 `http://localhost:5173/api/auth`，不要在同一流程混用 `localhost` 与 `127.0.0.1`。

1. 发送 `GET http://localhost:5173/api/auth/csrf`。响应在 Cookie 中写入 `smartmall_csrf`（HttpOnly、SameSite=Strict、Path=/api/auth），JSON 示例：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "headerName": "X-XSRF-TOKEN",
    "token": "这里是每次响应生成的掩码校验值"
  }
}
```

2. 保持同一浏览器/启用 Postman Cookie jar，让它自动携带上述 Cookie；复制 JSON 的 `data.token`，不要复制 Cookie 原始值。发送 `POST http://localhost:5173/api/auth/login`，添加以下请求头和 JSON 表单：

```http
Content-Type: application/json
X-XSRF-TOKEN: <上一步的 data.token>
```

直接访问 8080 的 `/auth/*` 时，Cookie jar 不会自动匹配 `/api/auth` 路径；手工联调优先使用上述代理路径。注册也使用相同步骤，最后 POST 到 `/api/auth/register`。

```json
{
  "username": "你已注册的用户名",
  "password": "对应的明文密码"
}
```

成功响应的 `data` 包含 `accessToken`、`expiresIn`（900 秒）和不含密码的 `user`。复制 `data.accessToken`，不要复制 JSON 引号。

随后发送 `GET http://localhost:5173/api/auth/me`，不需要请求体或 userId 参数：

```http
Authorization: Bearer <accessToken>
```

成功返回 HTTP 200，例如：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "username": "alice",
    "email": null
  }
}
```

用户 ID 取自通过验证的 JWT 的 `sub`，再查询数据库。查询参数 `userId` 不会改变当前身份。凭证有效但对应用户已不存在时，沿用用户不存在的 HTTP 404 业务响应。

### 安全过滤器错误响应

未提供 JWT，或 JWT 格式、签名、签发者、受众、时间校验失败时，受保护请求返回 HTTP 401，并保留 `WWW-Authenticate: Bearer`：

```json
{
  "code": 401,
  "message": "请先登录或重新登录",
  "data": null
}
```

已认证但被访问规则拒绝时返回 HTTP 403：

```json
{
  "code": 403,
  "message": "无权访问该资源",
  "data": null
}
```

CSRF Cookie/请求头缺失或校验不匹配时，也使用上述 403 JSON，并在进入 Controller/Service 之前拒绝请求。`permitAll` 表示无需登录，不表示跳过 CSRF。安全过滤器错误不返回底层异常细节；通过 CSRF 后，登录失败、参数校验、用户不存在等错误仍由全局异常处理器生成原有的 401/400/404 响应。

前端已接入上述登录和当前用户接口：`/api/auth/*` 在 Vite 开发环境代理到 8080 的 `/auth/*`。页面内存保存 accessToken，并仅在身份请求头中携带；刷新页面后丢失本页登录状态。登录后会再请求 `/auth/me`，而不是仅根据登录响应显示已认证。

双令牌改造进行中：登录 Service 已生成 `aud=smartmall-refresh`、86400 秒有效的签名 Refresh Token，内部通过 `LoginResultDTO` 交给 Controller；对外 JSON 仍只有原 `LoginResponse`。Controller 登录成功时通过 Set-Cookie 响应头发送 `smartmall_refresh`：HttpOnly、SameSite=Strict、Path=/api/auth、Max-Age=86400、不设置 Domain，默认 Secure=true（显式 local-http 开发配置为 false）。密码错误、表单校验失败或 CSRF 拒绝时不签发新的刷新 Cookie。CSRF Cookie 不是身份凭证。自动刷新、注销 Cookie、角色权限及订单归属授权仍未完成，页面刷新后仍需登录。

后续约定为 Access Token 留在内存、Refresh Token 放 HttpOnly Cookie、暂不落库，固定刷新令牌到期时间，不在每次刷新时延长。无服务端刷新会话状态，无法单独撤销被复制的刷新令牌或检测重放；这是开发阶段方案，不是完整生产认证体系。当前退出仍只清除本页凭证。

## Create User

`POST /users`

Request body:

```json
{
  "username": "alice",
  "email": "alice@example.com"
}
```

Successful response:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "username": "alice",
    "email": "alice@example.com"
  }
}
```

## List Users

`GET /users`

Optional query parameters:

| Parameter | Required | Default | Rules |
|---|---:|---:|---|
| `username` | No | empty | Exact username match |
| `email` | No | empty | Exact email match |
| `page` | No | `1` | Must be at least `1` |
| `size` | No | `1` | Must be between `1` and `100` |

Examples:

```text
GET /users
GET /users?username=alice&page=1&size=10
GET /users?email=alice@example.com&page=1&size=10
```

Successful response:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "records": [
      {
        "id": 1,
        "username": "alice",
        "email": "alice@example.com"
      }
    ],
    "current": 1,
    "size": 10,
    "total": 1,
    "pages": 1
  }
}
```

Results are ordered by `id` ascending. If no user matches the filters, `records` is an empty array and the response remains HTTP `200`.

## Get User

`GET /users/{id}`

Example: `GET /users/1`

Returns one user. If the ID does not exist, the service returns HTTP `404`.

## Count Users

`GET /users/count`

Returns the number of rows in the `users` table.

## Update User

`PUT /users/{id}`

Request body:

```json
{
  "username": "alice-updated",
  "email": "updated@example.com"
}
```

If the ID does not exist, the service returns HTTP `404`.

## Delete User

`DELETE /users/{id}`

If the ID does not exist, the service returns HTTP `404`.

## Validation Errors

`username` and `email` must not be blank. `email` must have a valid email format.
For list requests, `page` must be at least `1` and `size` must be between `1` and `100`.

Validation failures return HTTP `400` with the common response structure:

```json
{
  "code": 400,
  "message": "用户名不能为空",
  "data": null
}
```

## Common Response

All successful endpoints use `Result<T>`:

```json
{
  "code": 200,
  "message": "操作成功",
  "data": "具体数据"
}
```

## SmartMall 订单服务 API

基础地址：`http://localhost:8082`

### 取消订单

`PATCH /orders/{id}/cancel`

- 路径参数：`id`，要取消的订单 ID。
- 请求体：无。
- 业务规则：只有状态为 `PENDING_PAYMENT` 的订单可以取消。

取消成功时返回 HTTP `200`：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "totalAmount": 599.80,
    "status": "CANCELLED"
  }
}
```

订单不存在时返回 HTTP `404`：

```json
{
  "code": 404,
  "message": "订单不存在：1",
  "data": null
}
```

订单不是待支付状态（例如重复取消）时返回 HTTP `400`：

```json
{
  "code": 400,
  "message": "订单状态异常",
  "data": null
}
```
