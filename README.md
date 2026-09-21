# SmartMall

SmartMall 是一个面向学习与作品展示的“电商 + AI”微服务项目。当前已完成用户、商品、订单和 AI 服务，以及可直接演示的 Vue 3 商城前端。

## 已实现功能

- 用户服务：注册、BCrypt 密码校验、RSA JWT 登录、当前用户查询及统一安全错误响应；原有用户 CRUD 暂不对外开放
- 商品服务：商品 CRUD、名称/状态筛选、分页和库存信息
- 订单服务：跨服务查询商品、事务创建订单、订单详情、取消订单；查询本人订单、创建、详情和取消均使用验证后的 JWT 用户 ID，创建请求仅包含商品明细
- AI 服务：使用 Responses API 完成对话，支持模型调用只读工具查询在售商品和当前用户订单
- 商城前端：极简 AI 对话首页、右下角切换的整页商城、居中登录弹窗、右侧个人/商品/订单抽屉；共享内存身份，支持商品检索、分页、立即购买和订单管理
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

cd smartmall-ai-service
mvn spring-boot:run
```

上面的每个服务应在独立终端中从项目根目录进入对应文件夹启动。用户服务默认运行在 `8080`，商品服务为 `8081`，订单服务为 `8082`，AI 服务为 `8084`。AI 服务运行配置需要 `AGENTROUTER_API_KEY` 环境变量；用户、订单和 AI 服务的 JWT 公钥必须对应用户服务签发 Access Token 的私钥。私钥和 API Key 保持在仓库之外，不提交到 Git。

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
- 登录后点击右下角“逛商城”进入整页商城首页，“回到 AI”返回对话页；切换有轻量转场并尊重系统减少动态效果设置，不刷新页面或重新登录。聊天内容、草稿与搜索在本页切换时保留；主动退出/到期清空本页身份和相关状态，重新加载页面默认回到 AI 首页。
- 商城首页提供商品搜索、卡片列表、分页、立即购买和订单入口，使用现有真实接口；目前没有分类与图片字段，不虚构分类、促销或商品照片。卡片图案是占位展示，不是商品实拍。
- 右上角侧边栏仍可查看个人信息、商品信息和订单信息；关闭按钮、Esc 或点击抽屉外部可收起。商品/订单在首次进入相应入口时加载，未登录及仅停留 AI 首页时不预先加载。
- 对话区已接入 AI 服务：Enter 发送、Shift+Enter 换行，中文输入法确认候选字不发送。请求显式携带内存 Access Token；AI 可通过只读工具查询在售商品和当前用户订单，暂不执行下单、取消或退款；退出/刷新清空本页对话。
- 使用已注册账号登录。页面先通过同源代理领取 `/api/auth/csrf` 的校验值，再携带 `X-XSRF-TOKEN` 请求头提交登录；匹配的 HttpOnly CSRF Cookie 由浏览器自动携带。随后用 Access Token 请求 `/api/auth/me`，后者成功后才显示当前用户。登录和注册不再忽略 CSRF，手工请求步骤见 `API.md`。
- Access Token 仅保存在页面内存，不写入 localStorage、sessionStorage、Cookie 或 URL。刷新页面会丢失旧 Access Token，但有效的刷新 Cookie 可以换取新凭证恢复身份；CSRF Cookie 本身不能恢复身份。当前尚未接入页面停留期间的 Access Token 到期自动续期，到期仍提示登录。
- Service 签发独立用途、24 小时有效的 Refresh Token，Controller 登录成功时通过 Set-Cookie 响应头发送 `smartmall_refresh`（HttpOnly、SameSite=Strict、Path=/api/auth）。POST /auth/refresh、主动退出 /auth/logout 和页面启动恢复均已实现。
- 页面启动仅显示简洁聊天首页，在后台依次领取 CSRF、请求 refresh（Cookie 由浏览器携带、不发送 Bearer）、使用新 Access Token 查询 /auth/me；恢复期间不显示等待弹窗或登录表单，确认前侧栏和聊天输入保持禁用。成功后直接可用；401 才显示登录窗口，网络/服务异常提供重试或手动登录，不无限重试。
- UserService.refresh 验证 Refresh Token 后按可信 ID 查询用户，用户存在才签发新 Access Token 并返回用户信息，不重新签发 Refresh Token 或延长其期限。保持登录时使用同一网址，不混用 localhost 和 127.0.0.1。
- 主动退出已接入后端：清理本页状态，成功时浏览器删除刷新 Cookie；失败时明确提示并可重试退出。退出请求进行中禁止新登录。刷新/关闭页面不调用退出，不主动删除刷新 Cookie。
- 个人信息不提供手动确认身份按钮；登录和页面恢复时仍自动调用 `/auth/me` 验证身份。退出不会让已经被复制的 JWT 立即失效，也不会清除其他标签页内存中的 Access Token。
- 密码错误、后端不可用、凭证失效均显示提示，不显示 token。当前用户、“我的订单”、创建和取消订单请求显式携带 JWT，不全局附加给商品、登录、刷新或退出请求。
- “我的订单”使用 `/api/orders/me`，不再手填查询用户 ID；请求只携带当前内存 Access Token，不携带 Cookie、不缓存结果。列表 `401` 会清本页身份并显示登录窗口，网络/服务异常提供重试。退出、到期及切换账号会丢弃旧会话的未完成查询和结果。
- 立即购买使用同一个登录会话的 Access Token，不携带 Cookie；请求体仅发送一个商品的 `items`（商品 ID、数量为 1），不传 `userId`。提交期间禁用重复购买；成功后显示我的订单，`401` 清本页身份，业务失败不自动重放请求。网络/服务异常不能确定订单是否已创建，会提示先核对订单。
- **后端列表、创建、详情和取消已使用 JWT 中的用户 ID。前端订单列表可打开详情弹窗。新订单状态为 `NORMAL`；详情与取消会先验证订单归属，取消再按订单 ID、用户 ID、正常状态做条件更新。不存在或不属于当前用户的详情统一返回 404。**

## 核心调用链

```text
Vue 前端
  ├─ /api/auth     -> 用户服务 -> JWT 校验 / MyBatis-Plus -> MySQL
  ├─ /api/products -> 商品服务 -> MyBatis-Plus -> MySQL
  ├─ /api/orders   -> 订单服务 -> 商品服务 -> MyBatis-Plus -> MySQL
  └─ /api/chat     -> AI 服务 -> Responses API（SSE 流式输出）
                           ├─ 商品查询工具 -> 商品服务
                           └─ 本人订单工具 -> 订单服务（转发当前 Access Token）
```

创建订单时，订单服务先调用商品服务校验商品并获取价格；所有商品验证成功后，才在一个事务中写入订单主表和订单明细表。

AI 对话使用 `POST /chat` 保留 JSON 请求体和 Bearer Token，并通过 SSE 逐段返回模型文本。前端使用 `fetch` 读取响应流，因此不需要把请求降级为无法方便携带 POST body 的原生 `EventSource`。

## 构建验证

```powershell
cd smartmall-frontend
npm test
npm run build

cd ..\smartmall-order-service
mvn test
```

详细接口说明见项目根目录的 `API.md`。

`npm test` 使用 Node 内置测试器（当前运行环境 Node 22.15，使用实验性的 TypeScript 类型擦除选项），无需安装新的测试框架。浏览器交互检查见 `smartmall-frontend/scripts/auth-ui-smoke.mjs`，刷新恢复专项见 `smartmall-frontend/scripts/auth-restore-smoke.mjs`，订单查询及下单身份/跨会话隔离专项分别见 `smartmall-frontend/scripts/order-auth-smoke.mjs` 和 `smartmall-frontend/scripts/checkout-auth-smoke.mjs`：先启动 Vite，再在具备 Playwright 和 Chrome 的环境执行脚本；可用 `SMARTMALL_PLAYWRIGHT_MODULE` 指定环境提供的 Playwright 模块路径、`SMARTMALL_BASE_URL` 指定前端地址。检查拦截 API 使用模拟数据，不创建数据库账号，也不替代真实服务联调；截图保存在已忽略的 `artifacts/` 下。
