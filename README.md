# SmartMall

SmartMall 是一个面向学习与作品展示的“电商 + AI”微服务项目。当前已完成用户、商品、订单三个核心服务，以及可直接演示的 Vue 3 商城前端。

## 已实现功能

- 用户服务：用户 CRUD、条件筛选、分页、参数校验和统一异常响应
- 商品服务：商品 CRUD、名称/状态筛选、分页和库存信息
- 订单服务：跨服务查询商品、事务创建订单、订单详情、用户订单列表和取消订单
- 商城前端：商品检索、分页、购物车、创建订单、查询订单和取消待支付订单
- 公共模块：统一的 `Result<T>` 和 `PageResult<T>` 响应结构

## 技术栈

- Java 17、Spring Boot、MyBatis-Plus、Maven
- MySQL 8
- Vue 3、TypeScript、Vite
- 规划接入：Spring Cloud Alibaba、Redis、RocketMQ、PGVector 和 AI 商品问答

## 本地运行

请先启动 MySQL，并保证 `smartmall` 数据库及项目数据表已经创建。

分别启动后端服务：

```powershell
cd smartmall-product-service
mvn spring-boot:run

cd smartmall-order-service
mvn spring-boot:run
```

商品服务默认运行在 `8081`，订单服务默认运行在 `8082`。

再启动前端：

```powershell
cd smartmall-frontend
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。开发环境由 Vite 将商品和订单请求分别代理到对应的后端服务。

## 核心调用链

```text
Vue 前端
  ├─ /api/products -> 商品服务 -> MyBatis-Plus -> MySQL
  └─ /api/orders   -> 订单服务 -> 商品服务
                              -> MyBatis-Plus -> MySQL
```

创建订单时，订单服务先调用商品服务校验商品并获取价格；所有商品验证成功后，才在一个事务中写入订单主表和订单明细表。

## 构建验证

```powershell
cd smartmall-frontend
npm run build

cd ..\smartmall-order-service
mvn test
```

详细接口说明见项目根目录的 `API.md`。
