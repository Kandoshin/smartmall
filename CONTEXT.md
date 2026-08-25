# SmartMall Domain Context

## Product

商品是商城中可展示、可购买的销售对象。第一版商品包含名称、描述、价格、库存和上下架状态。

## Product Status

- `1`：上架，可被前台展示和购买。
- `0`：下架，不应被前台展示或购买。

## Price

价格表示货币金额，必须保持精确，不能接受浮点误差。

## Stock

第一版将当前库存视为商品的一部分。出现库存锁定、扣减并发、库存流水等需求后，再评估拆分独立库存模型。

## MVP Boundary

第一版暂不包含分类树、SKU、商品图片和独立库存表，避免在基础商品 CRUD 阶段引入多个业务概念。

## Order Relationship

一条 `orders` 记录表示一个订单；一个订单可以对应多条 `order_items` 记录。`order_items.order_id` 指向所属订单，这是一对多关系。
