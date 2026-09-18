# SmartMall Frontend

SmartMall 的 Vue 3 + TypeScript 前端，提供 AI 对话、商品查询、立即购买、用户订单查询和取消订单功能。

## 启动

先启动用户服务（8080）、商品服务（8081）、订单服务（8082）和 AI 服务（8084），再执行：

```powershell
npm install
npm run dev
```

访问 `http://localhost:5173`。

## 构建

```powershell
npm run build
```

开发环境的接口代理规则在 `vite.config.ts` 中维护。
