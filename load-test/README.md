# JMeter 秒杀压测准备

## 1. 生成 1000 个登录态

在项目根目录执行：

```bash
./scripts/prepare-jmeter-tokens.sh 1000
```

生成器会：

1. 从 MySQL 的 `tb_user` 按真实 ID 读取前 1000 个用户。
2. 为每个用户生成一个随机 UUID Token。
3. 把脱敏后的 `id`、`nickName`、`icon` 写入 Redis Hash：`login:token:{token}`。
4. 为每个 Redis 登录态设置 30 分钟 TTL。
5. 将同一批 Token 逐行写入 `load-test/tokens.txt`。
6. 将o `user_id,tken` 对应关系写入 `load-test/token-users.csv`，供人工核对。
7. 重新读取文件和全部 Redis Hash，核对 Token、用户 ID、字段和 TTL。
8. 将核对结论写入 `load-test/token-verification.txt`。

`tokens.txt`、映射文件和核对报告都已加入 `.gitignore`，不会被提交。

登录态有效期为 30 分钟。应在开始 JMeter 压测前重新运行生成脚本。

脚本重跑时会覆盖本地的三个生成文件，但不会主动删除上一轮 Redis Token，避免误删正在使用的登录态；上一轮 Token 会在 30 分钟后由 Redis 自动过期。

## 2. JMeter 公共配置

CSV Data Set Config：

```text
Filename: 项目绝对路径/load-test/tokens.txt
Variable Names: token
Recycle on EOF: False
Stop thread on EOF: True
Sharing mode: All threads
```

HTTP Header Manager：

```text
authorization: ${token}
```

HTTP Request：

```text
Method: POST
Path: /voucher-order/seckill/{测试优惠券ID}
```

正式压测不要在 JMeter GUI 中运行。GUI 用于编辑 `.jmx`，运行时使用非 GUI 命令并生成 HTML 报告：

```bash
jmeter -n -t seckill.jmx -l result.jtl -e -o report
```

## 3. 100 线程与 1000 线程的可比方案

两组测试都发送 1000 个请求、使用 1000 个不同用户，只有并发度不同：

| 场景 | 线程数 | 循环次数 | Token 数 | 总请求数 | Ramp-up |
|---|---:|---:|---:|---:|---:|
| A | 100 | 10 | 1000 | 1000 | 1 秒 |
| B | 1000 | 1 | 1000 | 1000 | 1 秒 |

CSV Sharing mode 必须为 `All threads`，这样场景 A 的每次循环也会继续读取下一行 Token，而不是重复同一个用户。

为了避免前一轮订单影响后一轮，应为两个场景准备库存、时间窗口完全相同的两个测试优惠券；或者仅在隔离的测试数据库中，在两轮之间恢复库存并清理对应测试订单。

建议先进行一次 20 线程预热，预热结果不计入对比。

重点比较 JMeter Aggregate Report 或 HTML 报告中的：

- Throughput：每秒处理请求数，越高越好。
- Average：平均响应时间。
- Median：50% 请求的响应时间。
- 90th / 95th / 99th percentile：尾部慢请求，尤其关注 95% 和 99%。
- Error %：失败比例；还要区分业务失败与 HTTP/系统错误。
- 实际成功订单数和库存变化，防止只看速度而忽略正确性。

如果 1000 线程相较 100 线程吞吐量没有明显增加，但 P95、P99 和错误率显著上升，说明系统已经接近或超过当前容量。不要只用平均响应时间判断。

## 4. 一人一单专项测试

另建一个线程组，让 100 个线程都使用同一个有效 Token，并对一个没有购买记录的新优惠券各请求一次。

预期：

- 最终只有一笔订单。
- 库存只减少 1。
- 其余请求返回“请求正在处理中”或“不能重复下单”。

数据库核对：

```sql
SELECT COUNT(*) AS order_count,
       COUNT(DISTINCT user_id) AS user_count
FROM tb_voucher_order
WHERE voucher_id = ?;

SELECT user_id, voucher_id, COUNT(*) AS order_count
FROM tb_voucher_order
WHERE voucher_id = ?
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;
```

第二条 SQL 应返回 0 行。
