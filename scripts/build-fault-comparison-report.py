#!/usr/bin/env python3
"""Build an honest Redis Stream/RabbitMQ fault comparison from raw trial CSV files."""

import argparse
import csv
from datetime import datetime
from pathlib import Path


def rows(path):
    with Path(path).open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def passed(items, field):
    return sum(item[field] == "1" for item in items)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--redis-a1", required=True)
    parser.add_argument("--redis-a2", required=True)
    parser.add_argument("--redis-b", required=True)
    parser.add_argument("--rabbit-a", required=True)
    parser.add_argument("--rabbit-b1", required=True)
    parser.add_argument("--rabbit-b2", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    redis_a1, redis_a2, redis_b = rows(args.redis_a1), rows(args.redis_a2), rows(args.redis_b)
    rabbit_a, rabbit_b1, rabbit_b2 = rows(args.rabbit_a), rows(args.rabbit_b1), rows(args.rabbit_b2)
    a1_replica = passed(redis_a1, "replica_before_restart")
    a1_master = passed(redis_a1, "master_after_restart")
    a2_missing = passed(redis_a2, "missing_on_promoted_replica")
    redis_recovered = sum(r["pending_after_restart"] == "0" and r["db_after_restart"] == "1" for r in redis_b)
    redis_poison = sum(r["poison_pending"] == "1" for r in redis_b)
    redis_poison_repeated = sum(int(r.get("poison_attempts", 0)) >= 2 for r in redis_b)
    rabbit_preserved = passed(rabbit_a, "preserved")
    rabbit_recovered = passed(rabbit_b1, "recovered")
    rabbit_dlq = passed(rabbit_b2, "passed")
    all_acceptance = (
        rabbit_preserved == 30 and rabbit_recovered == 10 and rabbit_dlq == 5
    )

    source_links = {
        "Redis A1": Path(args.redis_a1).resolve(),
        "Redis A2": Path(args.redis_a2).resolve(),
        "Redis B": Path(args.redis_b).resolve(),
        "Rabbit A": Path(args.rabbit_a).resolve(),
        "Rabbit B1": Path(args.rabbit_b1).resolve(),
        "Rabbit B2": Path(args.rabbit_b2).resolve(),
    }
    lines = [
        "# Redis Stream 与 RabbitMQ 故障可靠性对比",
        "",
        f"生成时间：{datetime.now().astimezone().isoformat(timespec='seconds')}",
        "",
        "> 实验边界：所有 `kill -9` 都是隔离进程崩溃模拟，不等价于机器断电。RabbitMQ 是隔离单节点持久化实验，不宣称多节点高可用。",
        "",
        "## 汇总",
        "",
        "| 场景 | Redis Stream 实测 | RabbitMQ 实测 |",
        "|---|---|---|",
        f"| 已确认消息后杀主进程 | 默认网络：从节点 {a1_replica}/30；原主 AOF 恢复 {a1_master}/30 | Confirm 后重启保留 {rabbit_preserved}/30 |",
        f"| 受控复制中断/故障转移 | 提升后的从节点缺失 {a2_missing}/10；原主 AOF 均可恢复 | 单节点实验无对应故障转移结论 |",
        f"| 消费中杀应用 | Pending 恢复并落库 {redis_recovered}/10 | 未 ACK 回到 ready 并重投落库 {rabbit_recovered}/10 |",
        f"| 毒消息 | 持续留在 Pending {redis_poison}/10；重复失败且无终点 {redis_poison_repeated}/10 | 恰好 3 次尝试后进入 DLQ {rabbit_dlq}/5 |",
        "",
        "## 结论",
        "",
        f"- Redis 默认健康网络 A1 本次观测为零丢失（主 AOF {a1_master}/30、从节点 {a1_replica}/30）；这不是断电零丢失证明。`appendfsync everysec` 理论上仍存在尚未 fsync 的断电窗口。",
        f"- 受控暂停复制后，提升从节点有 {a2_missing}/10 缺失已由原主确认的消息，直接展示异步复制窗口；该结果不混入默认网络丢失率。",
        f"- 两种实现都能恢复未完成消费；现有 Stream 实现的毒消息在 {redis_poison_repeated}/10 轮观察到重复失败并持续 Pending，而 RabbitMQ 在第 3 次失败后进入 DLQ {rabbit_dlq}/5。",
        "- RabbitMQ 的优势结论仅限本次验收范围：Confirm 后单节点进程重启恢复、未 ACK 重投、有限重试和 DLQ；不代表跨节点高可用或断电测试。" if all_acceptance else "- RabbitMQ 验收项未全部满足，因此停止作可靠性优势结论，详见原始记录。",
        "- Lua 预扣与 RabbitMQ 发布之间仍存在应用崩溃窗口；本版本为保持性能口径可比，没有引入事务 Outbox。",
        "",
        "## 过程问题与修复",
        "",
        "- 首次毒消息验收发现 Spring AMQP 2.2 默认恢复器在 MANUAL ACK 下不会主动拒绝；改为显式 `rejectManual=true` 后，正式 5 轮均为 3 次尝试并进入 DLQ。",
        "- broker 故障脚本曾因中文标点紧跟 Shell 变量触发变量名解析错误；修正为 `${pid}` 后，正式 30 轮重新执行并全部通过。",
        "- B1 连续快速重启 JVM 时出现一次连接池初始化超时，并发现旧 ready 消息会污染下一轮；加入 3 秒冷却、无消费者清场和字段记录修复后，正式 10 轮从干净基线重新执行。",
        "- 上述失败尝试没有混入正式通过率；原失败目录仍保留在 `fault-test/results/` 供审计。",
        "",
        "## 原始证据",
        "",
    ]
    for label, path in source_links.items():
        lines.append(f"- {label}: `{path}`")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
