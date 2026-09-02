# SmartMall Frontend

SmartMall 的 Vue 3 + TypeScript 前端，提供商品查询、购物车、创建订单、用户订单查询和取消订单功能。

## 启动

先启动商品服务（8081）和订单服务（8082），再执行：

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
