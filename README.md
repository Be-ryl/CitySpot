# hm-dianping

黑马点评后端项目。

## 商户数据

- 商户名称、分类、地址、经纬度、价格等结构化数据存放在 MySQL 的 `tb_shop` 表。
- 初始化数据位于 `src/main/resources/db/hmdp.sql`，不需要再复制到 RAG 文档目录。
- 商户详情使用 Redis 缓存；应用启动时会把商户坐标同步到 Redis GEO。

## 附近商户

请求示例：

```http
GET /shop/of/type?typeId=1&current=1&x=120.149192&y=30.316078
```

传入经纬度时，返回 5 公里内按距离升序排列的商户；不传经纬度时，保持普通数据库分页查询。

可通过环境变量 `SHOP_GEO_INITIALIZE_ON_STARTUP=false` 关闭启动时的 GEO 索引初始化。

## AI / RAG 数据边界

- 商户查询、附近推荐：由 Agent 调用受控的业务工具，查询 MySQL / Redis。
- 退款规则、优惠券说明等非结构化制度：后续放入独立知识库目录并构建向量索引。
- 地图 API 不是附近查询的必需项；只有地址转经纬度、地图展示或路线规划时才需要接入。
