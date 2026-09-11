# SmartMall User Service API

Base URL: `http://localhost:8080`

## 当前认证与访问规则

以下表格仅描述用户服务（8080）；订单服务（8082）的认证迁移情况见下方“SmartMall 订单服务 API”。商品服务尚未接入此认证链。

| 请求 | 当前访问条件 |
|---|---|
| `GET /auth/csrf` | 可匿名领取 CSRF 校验值，不产生登录身份 |
| `POST /auth/register` | 可匿名访问；必须通过 CSRF，再校验表单和业务规则 |
| `POST /auth/login` | 可匿名访问；必须通过 CSRF，再检查用户名、密码 |
| `POST /auth/refresh` | 不要求 Access Token；必须通过 CSRF，再验证 smartmall_refresh Cookie |
| `POST /auth/logout` | 不要求 Access Token；必须通过 CSRF，通知浏览器清除刷新 Cookie |
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

前端已接入上述登录和当前用户接口：`/api/auth/*` 在 Vite 开发环境代理到 8080 的 `/auth/*`。页面内存保存 accessToken，显式在 `/api/auth/me` 和 `/api/orders/me` 请求头中携带；不全局附加给其他请求。刷新页面后旧内存凭证丢失，页面会用刷新 Cookie 换取新 Access Token 恢复身份。登录或恢复后会再请求 `/auth/me`，而不是仅根据令牌响应显示已认证。

前端右下角支持在 AI 对话页与整页商城之间切换，属于本页视图切换，不产生新的登录/刷新请求。整页商城与侧栏共用购物车和“我的订单”视图。订单列表已接入下方的 `/orders/me` 并携带 Bearer，不再显示查询用户 ID 输入框；创建和取消仍处于迁移阶段，不能把列表接通视为所有订单功能已完成授权。

登录 Service 生成 `aud=smartmall-refresh`、86400 秒有效的签名 Refresh Token，内部通过 `LoginResultDTO` 交给 Controller；对外 JSON 仍只有原 `LoginResponse`。Controller 登录成功时通过 Set-Cookie 响应头发送 `smartmall_refresh`：HttpOnly、SameSite=Strict、Path=/api/auth、Max-Age=86400、不设置 Domain，默认 Secure=true（显式 local-http 开发配置为 false）。密码错误、表单校验失败或 CSRF 拒绝时不签发新的刷新 Cookie。CSRF Cookie 不是身份凭证。退出清 Cookie 与页面启动恢复已接入；角色权限及订单归属授权仍未完成。

当前约定为 Access Token 留在内存、Refresh Token 放 HttpOnly Cookie、暂不落库，固定刷新令牌到期时间，不在每次刷新时延长。无服务端刷新会话状态，无法单独撤销被复制的刷新令牌或检测重放；这是开发阶段方案，不是完整生产认证体系。主动退出清除本页内存及本浏览器刷新 Cookie，不是服务端撤销 JWT。

2026-09-10 内部准备：`JwtConfig.refreshJwtDecoder` 已实现，UserService 通过 `@Qualifier("refreshJwtDecoder")` 注入它；私有 `verifyRefreshToken` 检查空凭证、调用验证器并返回可信用户 ID。校验器使用配置的 RSA 公钥、只接受 RS256，校验 `iss=smartmall-user-service`、框架时间规则（保留默认时钟容差），要求存在 `exp`、`aud` 恰为 `["smartmall-refresh"]`、`sub` 是规范的正 Long 用户 ID。原 `jwtDecoder` 保持 Access Token 校验规则，并作为默认 Bean，普通 Bearer 请求不会使用刷新校验器。

`UserService.refresh` 已完成调用上述 helper、按可信 ID 查用户、用户存在才签发新 Access Token 并组装 LoginResponse；不签发新 Refresh Token 或延长其期限。POST `/auth/refresh` 已修正 Cookie 名称并通过真实 Controller/Service/过滤器/RSA 的 MockMvc 验证（数据库 Mapper 模拟）。前端 useAuth 在页面挂载时调用 refreshSession；恢复期间只显示聊天首页，不显示等待弹窗或登录表单，侧栏和聊天输入保持禁用。成功后启用页面，401 显示登录窗口，网络/服务异常提供重试，不自动循环刷新。

### 刷新 Access Token

通过 Vite 发送 `POST http://localhost:5173/api/auth/refresh`，无需 JSON 请求体或 Authorization 请求头。先按上方流程 GET `/api/auth/csrf`，携带响应 JSON 的掩码值作为 `X-XSRF-TOKEN` 请求头；浏览器同时携带 `smartmall_csrf` 和登录时收到的 `smartmall_refresh` Cookie。HttpOnly Cookie 无需也不能由前端 JavaScript 读取。

- 成功：HTTP 200，`Result<LoginResponse>`，data 含 accessToken、expiresIn=900、user；不返回新的刷新 Cookie。
- CSRF 缺失或无效：HTTP 403；不会进入刷新业务。
- 通过 CSRF 后，刷新 Cookie 缺失、非法、过期、用途不符或用户已删除：HTTP 401，`Result.failure(401, "登录已失效，请重新登录")`，不暴露验证细节。

不要给刷新请求附加过期的 Bearer Token，否则资源服务器过滤器会先拒绝请求。前端页面启动时依次请求 CSRF → refresh → me；仅最后的 me 请求附加新 Access Token。刷新 Cookie 缺失或过期时回到登录窗口；网络/服务异常可重试恢复或手动登录。当前只接入启动恢复，尚未实现页面停留期间 Access Token 到期自动续期。

### 主动退出登录

`POST http://localhost:5173/api/auth/logout`，无 JSON 请求体，不附加 Bearer Token。先 GET `/api/auth/csrf`，保持 Cookie 并携带 JSON 返回的掩码值作为 `X-XSRF-TOKEN` 请求头，方式与刷新接口一致。

- 成功：HTTP 200，`{"code":200,"message":"操作成功","data":null}`；响应通过 Set-Cookie 将 smartmall_refresh 设为空值、Max-Age=0，保持 Path=/api/auth、HttpOnly、SameSite=Strict 和当前 Secure 配置，不设置 Domain。
- 无刷新 Cookie 或 Cookie 无效时仍可成功，重复退出也安全；不查数据库、不重新签发令牌。
- CSRF 缺失或无效：HTTP 403，不发送删除刷新 Cookie 的指令。
- 清本浏览器 Cookie 不撤销已被复制的 Access/Refresh JWT，也不清除其他标签页已持有的 Access Token。

前端按钮已接入：点击后先取消旧身份请求并清除本页内存/对话/商城状态，显示登录弹窗；收到成功响应后显示“已退出登录”。请求失败则提示无法确认 Cookie 清除并提供“重试退出”，不误报完全退出。请求进行中禁止提交新登录，以免迟到的 Cookie 删除响应覆盖新登录。关闭页面或刷新不调用退出接口，保留刷新 Cookie；再次打开页面时，有效 Cookie 可恢复身份。主动退出成功后再刷新则应保持未登录。

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

### 当前迁移边界

订单服务已配置 Access JWT 公钥验证，并使用 Spring Boot 默认的 Bearer 安全过滤链。只接受 RS256，校验 `iss=smartmall-user-service`、`aud` 包含 `smartmall-api`、时间规则、必填 `exp` 和规范的正 Long 类型用户 ID（`sub`）。刷新用途令牌不能替代 Access Token。

目前只有列表查询已从 JWT 取得用户 ID；创建订单仍读取请求中的 `userId`，详情和取消仍缺少订单归属判断。认证不等于完成这些接口的授权。订单服务的安全错误仍使用框架默认响应，尚未统一为用户服务上面的错误 JSON。前端只给列表请求接入 Bearer，未给创建和取消附加凭证；这两个写操作仍不能视为可用的已认证交易流程，页面保留迁移提示。

### 查询我的订单

`GET /orders/me`

本地经 Vite 代理的完整地址：`http://localhost:5173/api/orders/me`。

- 请求体：无；不需要 `userId` 参数。
- 请求头：`Authorization: Bearer <登录或刷新响应中的 accessToken>`。
- Controller 从已认证 JWT 的 `sub` 取得用户 ID，再交给原 Service 按该 ID 查询。
- 列表按 `createdAt` 倒序；尚无 `id` 次级排序。

成功返回 HTTP `200`，示例：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    { "id": 101, "totalAmount": 199.00, "status": "PENDING_PAYMENT" }
  ]
}
```

无订单仍返回 HTTP `200`，`data` 为 `[]`。缺少凭证、凭证无效或过期（超过框架时钟容差）、误用刷新令牌、`sub` 不合法时返回 HTTP `401`，不会进入订单查询业务。

附加 `?userId=8` 不会改变查询身份：若 JWT 的 `sub` 为 `"7"`，仍查询用户 7。旧 `GET /orders?userId=8` 已移除；有效 Bearer 下返回 `405 Method Not Allowed`（同路径仍有 `POST /orders`），不是旧查询入口。

前端打开订单视图时调用 `getMyOrders`，以 `cache: no-store` 避免缓存个人订单，以 `credentials: omit` 不发送 Cookie，只显式携带当前内存 Access Token。现有同一个 `useAuth` 会话向商城提供查询回调，组件不会创建另一套登录状态或自己解析 JWT。

- 未登录/恢复中不加载订单；恢复成功后使用新 Access Token。
- 订单返回 `401`（包括空响应体）时清本页登录状态、显示登录弹窗，不自动刷新或重试；不调用退出接口或删除刷新 Cookie。
- 网络、`403`、服务异常只显示订单错误和“重新加载”，不当成凭证过期、不清除登录身份。
- 离开订单视图取消未完成的列表请求；退出/过期清会话也取消查询，迟到结果不能进入后续会话。列表为空显示“你还没有订单”。

### 取消订单

`PATCH /orders/{id}/cancel`

以下保留已有业务层契约；本阶段尚未完成此接口的归属校验及受保护 HTTP 联调。

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
