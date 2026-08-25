# SmartMall 学习档案

这份文件用于记录学习目标、知识掌握程度和阶段准入标准。项目推进不以“代码写出来”为唯一完成条件；每个阶段都必须能解释代码、修改代码并独立完成小练习。

## 评估等级

| 等级 | 含义 |
|---|---|
| 0 - 未接触 | 没有形成概念，无法说明用途 |
| 1 - 认识 | 看过示例，能说出大致作用，但不能独立使用 |
| 2 - 能修改 | 能在已有代码上完成小改动，并能解释主要步骤 |
| 3 - 能独立 | 能从需求设计并完成小功能，能定位常见错误 |
| 4 - 能迁移 | 能把知识应用到新模块，并能说明设计取舍 |

## 当前评估（2026-08-24）

| 主题 | 当前等级 | 证据与下一步 |
|---|---:|---|
| Java 类、方法、字段 | 2 | 能阅读并修改类和方法；需要加强从零编写和类型判断 |
| Java 泛型 `Result<T>` | 2 | 能解释 `Result.success`、`T` 和响应字段；需要独立写出泛型方法 |
| Lambda 表达式 | 2 | 能在讲解后用 `->` 和方法引用表达字段读取，并能判断是否立即调用；需要在新场景中独立使用 |
| Spring Bean 与依赖注入 | 2 | 理解 Controller、Service、Repository 和构造器注入；需要独立解释 Bean 生命周期 |
| HTTP 与 REST | 3 | 能区分 GET、POST、PUT、DELETE、路径参数和查询参数 |
| DTO 与 Entity | 2 | 能说明请求 DTO、响应 DTO、数据库实体的区别；已能解释 `User` 不负责数据库访问 |
| 参数校验与异常处理 | 2 | 已实现 `@Valid`、全局异常和 400/404；需要补充异常分类设计 |
| MySQL 基础 | 2 | 能建库、建表并理解自增主键；需要加强 SQL 查询和索引基础 |
| JDBC / JdbcTemplate | 1 | 能跟读查询和插入代码；需要独立写简单 CRUD SQL |
| MyBatis-Plus | 2 | 已完成 CRUD 迁移，并在讲解下完成 `QueryWrapper` 条件查询；下一步需独立写条件组合和分页 |
| Maven | 2 | 能区分 POM、compile、test，并能诊断 JDK/目录问题 |
| JUnit / Mockito | 2 | 能运行测试并用 Mock 准备分页数据；已理解 `when` 是规定返回行为，`verify` 是检查调用，仍不必深入 Mockito 高级语法 |
| 调试与错误定位 | 2 | 能根据编译错误修正路径、类型和依赖问题；需要建立“先读第一处错误”的习惯 |

总体判断：目前处于“能在讲解下完成小功能”的阶段，尚未达到“能独立设计用户模块”的阶段。暂不应该继续快速扩展微服务数量。

## Recent Evidence (2026-08-20)

- 能独立在 MySQL 中创建并检查 `orders` 与 `order_items` 表，能根据 `DESC` 输出确认主键、自增、金额精度、索引和明细外键；下一步需要把数据库字段映射到 Java Entity，并理解一对多关系不会因两个 Entity 同时存在而自动查询。

- 能正确解释方法参数 `UserCreateRequest request` 中的类型和变量名。
- 能解释 `Result.success(...)` 会把业务结果包装成统一响应对象。
- 能区分 `User`、`UserDTO` 和 `UserCreateRequest` 的边界。
- 能理解 `UserMapper` 继承 `BaseMapper<User>` 后由 MyBatis-Plus 在运行时提供实现。
- 能初步理解 `User` 到 `UserDTO` 的转换是字段投影：DTO 可以只暴露部分字段，避免直接暴露实体；仍需巩固 Java 泛型列表不能因字段相似而直接互相赋值，以及 `map`/`toList` 的执行顺序。
- 能说明必须先逐个转换元素、再把转换结果收集为列表；能说明不调用 `user.getPassword()` 就不会把该字段写入 DTO。下一步练习普通 `for` 循环与 Stream 的等价转换。
- 能独立说出普通循环的核心步骤：创建 DTO、复制字段、加入结果列表；本次暴露出仍需巩固 `private` 字段访问、getter、构造器和 Java 命名规范。
- 能区分 Lombok 的读取和赋值能力：只使用 `@Getter` 时仍可调用 getter，但不能调用由 `@Data`/`@Setter` 生成的 setter；此前错误答案源于把问题看成了能否赋值。
- 需要复习 `User` 的实体职责；已说明 `UserDTO` 的值由 `@AllArgsConstructor` 生成的全参数构造方法写入，即使没有 setter 也能正常作为响应对象使用。
- 能说明 `User` 通过 setter 接收 `UserCreateRequest` 的字段，并能说明 `UserDTO` 通过构造方法获得值；已开始区分 `UserMapper` 与 `UserRepository`，但仍需巩固两者都是持久化入口、实现技术不同。
- 能正确判断 `userMapper.selectById(3L)` 返回单个 `User`，并区分它与 `selectList(null)` 返回的 `List<User>`；仍需巩固 MyBatis-Plus 方法签名和参数类型。
- 能正确判断 `selectList(null)` 在有 3 条记录时返回 `List<User>` 且包含 3 个元素；集合与元素类型的区分有进展。
- 能独立解释按 ID 查询不存在时的失败链路：Mapper 返回 `null`，Service 抛出业务异常，全局异常处理器映射为 HTTP 404。
- 已完成用户 CRUD 从 `UserRepository`/JdbcTemplate 到 `UserMapper`/MyBatis-Plus 的迁移，测试通过；主代码和测试已无 `UserRepository` 引用，下一步可清理旧 Repository 文件并复查未使用依赖。
- 在讲解下完成 `GET /users?username=...` 可选查询参数：Controller 接收 `@RequestParam`，Service 构造 `QueryWrapper`，Mapper 按条件查询；已测试无参数、匹配和无匹配三种结果。
- 能独立解释 `@RequestParam(required = false)` 允许省略参数并查询全部，也能选择 `like` 实现模糊匹配；条件查询基础达到“能修改并解释”。
- 独立完成双条件筛选代码：`username` 和 `email` 均为可选参数，非空时分别加入 `eq` 条件，两个条件默认 AND 组合；`mvn clean test` 通过。当前达到条件查询“能修改并解释”，尚未验证独立测试设计。
- 能区分 `user.getUsername()` 的立即调用和 `User::getUsername` 的方法引用，并能写出 `user -> user.getEmail()` 的等价 Lambda。
- 遇到 `PaginationInnerInterceptor` 无法解析时，定位为 MyBatis-Plus 3.5.17 分离出的 `mybatis-plus-jsqlparser` 依赖缺失；补充同版本依赖后 `mvn clean test` 通过。
- 分页参数校验首次返回 500 时，定位到 URL 参数校验异常未被全局处理器映射；新增 `ConstraintViolationException` 到 HTTP 400 的处理，构建测试通过。需要通过重启服务验证运行时响应。
- 已在 Service 和 Controller 中接通 `UserPageResult` 分页响应，包含当前页记录、页码、每页大小、总记录数和总页数；`mvn clean test` 通过，待 Postman 验证真实 JSON 结构。
- 分页参数增加 `@Max(100)` 上限，并完成分页阶段基线验证；下一步补充 MockMvc 对分页响应和非法参数的自动测试。
- 独立补充 `UserService` 分页单元测试：用 Mock `selectPage` 返回准备好的 `Page<User>`，验证 `UserPageResult` 元数据和 DTO 转换；测试总数达到 12，构建成功。
- 学习节奏调整：测试采用“每个功能至少一个成功和一个关键失败场景”的最低门槛，不在 Mockito 细节上长时间停留；优先推进可解释的业务功能。
- 能参与商品领域建模：理解金额使用精确类型、状态值含义，以及 MVP 阶段暂不拆分库存模型；下一步创建商品服务骨架。
- 在商品服务中复用已掌握的分层模式，完成创建、列表、按 ID 查询及商品专用全局异常处理；新模块基础 CRUD 能在较少讲解下迁移。
- 商品服务已补齐更新和删除，继续使用 `updateById`/`deleteById` 和受影响行数判断 404；本次由助手实现，用户后续需通过接口验证并能解释请求链路。
- 下一步将把已掌握的条件查询和分页迁移到商品领域，重点观察领域字段 `status` 和模糊名称查询，不重复讲解基础分页语法。
- 已完成商品列表增强：`name` 使用 `like`，`status` 使用 `eq`，并接入 `Page<Product>`、稳定排序和参数校验；这次主要由助手实现，用户需要通过接口复述参数到 Mapper 的链路。
- 商品查询增强已完成验证；下一阶段学习 Maven Jar 公共模块和泛型 `PageResult<T>`，目标是理解复用边界而不是复制业务类。
- 已完成公共模块迁移：理解公共模块不放业务实体，使用泛型 `PageResult<T>` 复用分页结构；两个服务已切换到公共 `Result`/`PageResult` 且构建通过。
- 当前仍不能独立解释 `List<User>` 到 `List<UserDTO>` 的 Stream、`map` 和 `toList` 数据转换，因此暂停继续迁移。

当前门槛：在继续 MyBatis-Plus 条件查询之前，完成一次不看示例的 `getUsers()` 数据流解释，并能说明 Mapper、Entity 和 DTO 的协作关系。

## 阶段目标与准入标准

### 阶段 1：Java 与 Spring Boot 基础

必须掌握：类、对象、方法、字段、构造方法、接口、泛型、异常、依赖注入、Bean。

通过标准：不看示例，能解释并手写一个简单的 `Result<T>`、一个 Service 和一个构造器注入；能读懂常见编译错误。

### 阶段 2：REST API 与分层

必须掌握：HTTP 方法、URL 路径、路径参数、查询参数、请求体、Controller/Service/Repository 职责、DTO。

通过标准：给出一个新需求时，能先画出请求链路并说明每层放什么代码；能独立增加一个接口而不把 SQL 写进 Controller。

### 阶段 3：MySQL 与持久化

必须掌握：表、主键、自增、增删改查、参数占位符、事务的基本概念、数据库数据与内存数据的区别。

通过标准：能独立写出用户表 CRUD SQL，解释自增 ID 不连续的原因，并能根据 SQL 错误定位问题。

### 阶段 4：测试与质量

必须掌握：单元测试、集成测试、MockMvc、Mockito、断言、测试边界和失败场景。

通过标准：每个新增业务至少设计一个成功场景和一个失败场景；能说明测试隔离了什么、为什么使用 Mock。

### 阶段 5：MyBatis-Plus

必须掌握：Entity、Mapper、`BaseMapper`、字段映射、DTO 转换、查询条件、分页。

通过标准：能不用照抄示例，独立完成一个实体的列表、按条件查询、插入、更新和删除，并能解释 SQL 是如何生成的。

### 阶段 6：微服务基础

必须掌握后再拆分服务：模块边界、服务间调用、网关、配置、注册发现、统一错误和接口契约。

通过标准：能说明为什么拆分、服务之间传什么数据、失败如何处理，而不是只会复制配置文件。

### 阶段 7：Redis、消息和 AI 服务

只有前面阶段稳定后再进入。每项技术都必须先完成一个可验证的小实验，再接入 SmartMall 主流程。

## 学习规则

1. 每引入一个新概念，先说明它解决的问题，再写最小代码。
2. 每个阶段结束进行口头问题和小型独立练习。
3. 连续两次无法解释某段代码时，暂停扩展功能，回到最小例子。
4. 代码可以由助手检查，但关键类和方法尽量由学习者自己输入。
5. 不能只以“测试通过”作为掌握标准，还要能解释数据流和失败路径。

## 下一次检查重点

- 能否独立解释 `UserController -> UserService -> UserMapper -> MySQL` 的完整链路。
- 能否解释 `User`、`UserDTO`、`UserCreateRequest` 为什么是三个不同角色。
- 能否独立写出一个 MyBatis-Plus 的按条件查询。
- 能否为新接口设计成功、参数错误、资源不存在三类测试。
