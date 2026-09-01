# SmartMall User Service API

Base URL: `http://localhost:8080`

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
