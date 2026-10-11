# 秒杀异步链路可靠性与性能实验

项目保留原 `POST /voucher-order/seckill/{id}`（Lua + Redis Stream），并新增 `POST /voucher-order/seckill/mq/{id}`（Lua 预扣 + RabbitMQ Confirm）。两套接口共用 JMX 与指标统计，不得在同一时刻操作同一测试券。

## 隔离端口

| 组件 | 端口 | 用途 |
|---|---:|---|
| 原 Redis / 原应用 | 6379 / 8081 | 保留，不由故障脚本停止 |
| Redis 主 / 从 | 6380 / 6381 | Stream 故障实验，AOF everysec、异步复制 |
| RabbitMQ / 管理页 / 分布式端口 | 5673 / 15673 / 25673 | 项目隔离单节点 |
| Stream / Rabbit 故障应用 | 8082 / 8083 | 仅测试脚本可执行 `kill -9` |

RabbitMQ 数据位于 `fault-test/runtime/rabbitmq/mnesia`，脚本重启始终复用该目录。它是单节点持久化实验，不代表多节点高可用。

## 固定压测模型

| 场景 | 线程 | 总样本 | Ramp-up | 线程组循环 | 内部请求循环 | 轮数 |
|---|---:|---:|---:|---:|---:|---:|
| A | 1000 | 1000 | 1 秒 | 1 | 1 | 10 |
| B | 100 | 1000 | 1 秒 | 1 | 10 | 10 |

每轮严格执行健康及中间件检查、数据重置、1000 Token 校验、冒烟、再次恢复基线、JMeter、等待队列排空、DB/Redis/重复用户/DLQ 业务校验。失败时立即停止并保留现场。

## 准备和运行

`.env.local` 至少提供 `MYSQL_PASSWORD`。JMeter 会自动发现项目同级目录下的 `apache-jmeter-5.6.3/bin/jmeter`，也可通过 `JMETER_BIN` 指定。

```bash
./scripts/prepare-jmeter-tokens.sh 1000
./scripts/rabbitmq-isolated.sh start
mvn clean package
```

启动 Stream 应用 8081 后运行：

```bash
BACKEND=stream ./scripts/run-seckill-benchmark.sh
```

启动 RabbitMQ 应用 8083（Rabbit 开启、Stream 消费者关闭）后运行：

```bash
./scripts/rabbitmq-app-isolated.sh start
BACKEND=rabbit ./scripts/run-seckill-benchmark.sh
./scripts/rabbitmq-app-isolated.sh stop
```

应用控制脚本适合普通终端使用；在会自动清理子进程的 CI/代理环境中，应把应用作为独立前台任务保持运行。

每次结果目录包含 20 个 JTL、20 个 HTML 报告、逐轮 `summary.json`、队列积压 CSV、`round-metrics.csv` 和最终 `benchmark-report.md`。

## 故障实验

```bash
# Redis：A1 30轮、A2 10轮、消费者崩溃/毒消息 10轮
./scripts/run-redis-stream-fault-tests.sh all

# RabbitMQ：broker 重启30轮、未ACK重投10轮、毒消息DLQ 5轮
./scripts/run-rabbitmq-fault-tests.sh all
```

两个脚本仅会对 PID、监听端口与命令行均匹配的 6380/6381、8082/8083 或隔离 RabbitMQ 进程执行 `kill -9`。不会停止 Redis 6379 或应用 8081。

Redis A1 默认健康网络与 A2 复制暂停对照必须分别统计。`kill -9` 不是断电；AOF everysec 尚未 fsync 的断电风险需作为原理边界说明。RabbitMQ 故障实验必须在获得 Confirm ACK 后再杀 broker；毒消息要求恰好 3 次尝试后进入 DLQ。

## 可靠性边界

- 明确 NACK 或无法路由时，补偿 Lua 通过 `SREM` 返回值保证幂等撤销预扣。
- Confirm 超时/连接异常属于结果未知，不补偿，避免消息实际已持久化后错误回补造成超卖。
- Redis Lua 与 RabbitMQ 发布之间仍有应用崩溃窗口；本版本没有事务 Outbox，这是性能对比的明确限制。
- 消费者并发和 prefetch 都是 1；事务返回后才手动 ACK，失败最多尝试 3 次、固定间隔 500ms，最终拒绝且不 requeue，由 broker 路由到 DLQ。
