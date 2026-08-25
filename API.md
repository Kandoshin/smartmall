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
