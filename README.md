# SmartMall

SmartMall 是一个面向学习与作品展示的“电商 + AI”微服务项目。当前已完成用户、商品、订单三个核心服务，以及可直接演示的 Vue 3 商城前端。

## 已实现功能

- 用户服务：注册、BCrypt 密码校验、RSA JWT 登录、当前用户查询及统一安全错误响应；原有用户 CRUD 暂不对外开放
- 商品服务：商品 CRUD、名称/状态筛选、分页和库存信息
- 订单服务：跨服务查询商品、事务创建订单、订单详情、用户订单列表和取消订单
- 商城前端：极简对话首页、居中登录弹窗、右侧个人/商品/订单抽屉；保留内存凭证、商品检索、分页、购物车和订单演示（AI 尚未接入）
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
cd smartmall-user-service
mvn spring-boot:run "-Dspring-boot.run.profiles=local-http"

cd smartmall-product-service
mvn spring-boot:run

cd smartmall-order-service
mvn spring-boot:run
```

上面的每个服务应在独立终端中从项目根目录进入对应文件夹启动。用户服务默认运行在 `8080`，商品服务为 `8081`，订单服务为 `8082`。用户服务还需要 JWT 配置指向有效的 RSA 密钥文件；私钥保持在仓库之外，不提交到 Git。

`local-http` 仅供本机回环地址的 HTTP 开发：将 `smartmall.auth.cookie-secure` 设为 `false`，让浏览器可以在本地 HTTP 请求中使用 Cookie；HttpOnly、SameSite=Strict 和 CSRF 校验仍保留。默认配置为 Secure=true，正式部署必须使用 HTTPS，不得启用该开发配置。IDE 启动时可在运行配置的 Active profiles 中填写 `local-http`。

再启动前端：

```powershell
cd smartmall-frontend
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。开发环境由 Vite 将 `/api/auth`、商品和订单请求分别代理到对应服务。修改 Vite 配置后如未自动重启，可停止后重新运行 `npm run dev`。生产部署需要另行配置同源反向代理，Vite 的开发代理不会打包进静态页面。

### 前端登录说明

- 首页只显示对话入口。未登录时必须先完成居中弹窗登录；后端身份确认成功后弹窗消失，退出或令牌过期会重新弹出。
- 登录后点击右上角按钮展开侧边栏，可切换个人信息、商品信息和订单信息；关闭按钮、Esc 或点击抽屉外部可收起。商品/订单数据在首次进入对应分区时加载，不在未登录首页请求。
- 对话区目前仅为界面预览：Enter 发送、Shift+Enter 换行，中文输入法确认候选字不发送。预览消息不会调用 AI 或执行交易；退出/刷新清空对话。
- 使用已注册账号登录。页面先通过同源代理领取 `/api/auth/csrf` 的校验值，再携带 `X-XSRF-TOKEN` 请求头提交登录；匹配的 HttpOnly CSRF Cookie 由浏览器自动携带。随后用 Access Token 请求 `/api/auth/me`，后者成功后才显示当前用户。登录和注册不再忽略 CSRF，手工请求步骤见 `API.md`。
- Access Token 仅保存在页面内存，不写入 localStorage、sessionStorage、Cookie 或 URL；当前页面刷新、退出或令牌到期后仍需重新登录。CSRF Cookie 不是登录凭证，不能恢复身份。
- 双令牌改造进行中：Service 已签发独立用途、24 小时有效的 Refresh Token，Controller 登录成功时通过 Set-Cookie 响应头发送 `smartmall_refresh`（HttpOnly、SameSite=Strict、Path=/api/auth）。刷新接口和页面自动恢复仍未实现，不代表已经支持持久登录；退出目前也尚未清除此 Cookie。
- “确认当前身份”会再次调用 `/auth/me`，不是刷新令牌，不会延长有效期。退出只清除本页凭证，不会让已经签发的 JWT 立即失效。
- 密码错误、后端不可用、凭证失效均显示提示，不显示 token。当前只有身份请求携带 JWT，不会自动将它发给商品/订单服务。
- **订单仍是手填用户 ID 的教学演示，后端尚未接入身份与归属校验，不能视为已经完成用户隔离或直接对外上线。**

## 核心调用链

```text
Vue 前端
  ├─ /api/auth     -> 用户服务 -> JWT 校验 / MyBatis-Plus -> MySQL
  ├─ /api/products -> 商品服务 -> MyBatis-Plus -> MySQL
  └─ /api/orders   -> 订单服务 -> 商品服务
                              -> MyBatis-Plus -> MySQL
```

创建订单时，订单服务先调用商品服务校验商品并获取价格；所有商品验证成功后，才在一个事务中写入订单主表和订单明细表。

## 构建验证

```powershell
cd smartmall-frontend
npm test
npm run build

cd ..\smartmall-order-service
mvn test
```

详细接口说明见项目根目录的 `API.md`。

`npm test` 使用 Node 内置测试器（当前运行环境 Node 22.15，使用实验性的 TypeScript 类型擦除选项），无需安装新的测试框架。浏览器交互检查另见 `smartmall-frontend/scripts/auth-ui-smoke.mjs`：先启动 Vite，再在具备 Playwright 和 Chrome 的环境执行该脚本；可用 `SMARTMALL_PLAYWRIGHT_MODULE` 指定环境提供的 Playwright 模块路径、`SMARTMALL_BASE_URL` 指定前端地址。该检查拦截 API 使用模拟数据，不创建数据库账号，也不替代真实服务联调；截图保存在已忽略的 `artifacts/` 下。
