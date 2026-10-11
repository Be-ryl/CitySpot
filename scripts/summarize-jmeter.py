#!/usr/bin/env python3
"""Summarize per-round JMeter CSV JTL files and build the final benchmark report."""

import argparse
import csv
import json
import math
import statistics
from datetime import datetime
from pathlib import Path


METRIC_FIELDS = (
    "samples",
    "average_ms",
    "median_ms",
    "p90_ms",
    "p95_ms",
    "p99_ms",
    "min_ms",
    "max_ms",
    "error_rate_pct",
    "tps",
)


def percentile(values, fraction):
    ordered = sorted(values)
    index = max(0, math.ceil(fraction * len(ordered)) - 1)
    return ordered[index]


def read_backlog(path):
    backlog_path = Path(path)
    if not backlog_path.exists():
        return 0, 0
    with backlog_path.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    return (
        max((int(row["xlen"]) for row in rows), default=0),
        max((int(row["pending"]) for row in rows), default=0),
    )


def summarize_round(args):
    with Path(args.jtl).open(encoding="utf-8", newline="") as handle:
        rows = [row for row in csv.DictReader(handle) if row.get("label") == "POST 秒杀入队"]
    if not rows:
        raise SystemExit(f"JTL 没有找到压测样本：{args.jtl}")

    elapsed = [int(row["elapsed"]) for row in rows]
    errors = sum(row.get("success", "").lower() != "true" for row in rows)
    enqueue_success = sum(row.get("business_success", "").lower() == "true" for row in rows)
    started_at = min(int(row["timeStamp"]) for row in rows)
    finished_at = max(int(row["timeStamp"]) + int(row["elapsed"]) for row in rows)
    duration_seconds = max((finished_at - started_at) / 1000.0, 0.001)
    max_xlen, max_pending = read_backlog(args.backlog)

    over_sell = args.db_orders > args.initial_stock
    missing_orders = args.db_orders < enqueue_success
    extra_orders = args.db_orders > enqueue_success
    stock_consistent = (
        args.db_stock == args.initial_stock - args.db_orders
        and args.redis_stock == args.initial_stock - enqueue_success
    )
    business_pass = not any(
        (over_sell, missing_orders, extra_orders, args.duplicate_users > 0,
         not stock_consistent, args.dlq > 0)
    )

    summary = {
        "backend": args.backend,
        "scene": args.scene,
        "threads": args.threads,
        "round": args.round,
        "samples": len(rows),
        "average_ms": statistics.fmean(elapsed),
        "median_ms": statistics.median(elapsed),
        "p90_ms": percentile(elapsed, 0.90),
        "p95_ms": percentile(elapsed, 0.95),
        "p99_ms": percentile(elapsed, 0.99),
        "min_ms": min(elapsed),
        "max_ms": max(elapsed),
        "error_rate_pct": errors * 100.0 / len(rows),
        "tps": len(rows) / duration_seconds,
        "duration_seconds": duration_seconds,
        "enqueue_success": enqueue_success,
        "db_orders": args.db_orders,
        "db_distinct_users": args.db_distinct_users,
        "db_stock": args.db_stock,
        "redis_stock": args.redis_stock,
        "duplicate_users": args.duplicate_users,
        "stream_xlen": args.stream_xlen,
        "stream_pending": args.stream_pending,
        "dlq": args.dlq,
        "max_observed_xlen": max_xlen,
        "max_observed_pending": max_pending,
        "over_sell": over_sell,
        "missing_orders": missing_orders,
        "extra_orders": extra_orders,
        "stock_consistent": stock_consistent,
        "business_pass": business_pass,
        "business_validation": (
            f"{'PASS' if business_pass else 'FAIL'} "
            f"(入队={enqueue_success}, 订单={args.db_orders}, "
            f"DB库存={args.db_stock}, Redis库存={args.redis_stock}, "
            f"重复用户={args.duplicate_users}, DLQ={args.dlq})"
        ),
    }
    output = Path(args.output)
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


def average(summaries, field):
    return statistics.fmean(float(summary[field]) for summary in summaries)


def fmt_number(value, digits=1):
    return f"{value:.{digits}f}"


def comparison_sentence(label, actual, baseline, lower_is_better):
    change = (actual - baseline) * 100.0 / baseline
    if lower_is_better:
        word = "下降" if change < 0 else "上升"
    else:
        word = "提升" if change > 0 else "下降"
    return f"{label}{word} {abs(change):.1f}%"


def write_report(args):
    root = Path(args.results_dir)
    by_scene = {}
    all_summaries = []
    for scene in ("A", "B"):
        summaries = []
        for path in sorted((root / f"scene-{scene}").glob("round-*/summary.json")):
            summaries.append(json.loads(path.read_text(encoding="utf-8")))
        by_scene[scene] = summaries
        all_summaries.extend(summaries)

    if not all_summaries:
        raise SystemExit(f"没有找到轮次摘要：{root}")

    metrics_csv = root / "round-metrics.csv"
    csv_fields = [
        "backend", "scene", "threads", "round", *METRIC_FIELDS,
        "enqueue_success", "db_orders", "db_stock", "redis_stock",
        "duplicate_users", "stream_xlen", "stream_pending",
        "max_observed_xlen", "max_observed_pending", "dlq", "business_pass",
    ]
    with metrics_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=csv_fields)
        writer.writeheader()
        for summary in all_summaries:
            writer.writerow({field: summary.get(field, "") for field in csv_fields})

    is_rabbit = args.backend == "rabbit"
    current_label = "RabbitMQ" if is_rabbit else "Lua+Redis Stream"

    lines = [
        f"# {current_label} 秒杀压测报告",
        "",
        f"生成时间：{datetime.now().astimezone().isoformat(timespec='seconds')}",
        "",
    ]
    if (root / "jmeter-aggregate-report.png").exists():
        lines.extend(["聚合报告截图：[jmeter-aggregate-report.png](jmeter-aggregate-report.png)", ""])
    lines.extend([
        "## 横向对比",
        "",
        "| 方案 | 并发 | 轮次 | 平均RT | 中位数 | P90 | P95 | P99 | TPS | 异常率 | 业务校验 |",
        "|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---|",
        "| 同步DB（历史基准） | 1000 | 1次 | 1298ms | - | - | - | 1658ms | 460.4 | 0% | - |",
        "| 同步DB（历史基准） | 100 | 1次 | 107ms | - | - | - | 787ms | 619.6 | 0% | - |",
    ])

    if is_rabbit:
        lines.extend([
            "| Lua+Redis Stream（已验收） | 1000 | 10轮均值 | 265.8ms | 252.6ms | 433.3ms | 506.9ms | 561.9ms | 1683.2 | 0% | PASS：无超卖、无漏单 |",
            "| Lua+Redis Stream（已验收） | 100 | 10轮均值 | 26.1ms | 22.6ms | 51.4ms | 64.4ms | 78.9ms | 1865.4 | 0% | PASS：无超卖、无漏单 |",
        ])

    for scene in ("A", "B"):
        summaries = by_scene[scene]
        if not summaries:
            continue
        validation = "PASS：无超卖、无漏单" if all(s["business_pass"] for s in summaries) else "FAIL：详见轮次摘要"
        lines.append(
            "| {label} | {threads} | {rounds}轮均值 | {avg}ms | {median}ms | "
            "{p90}ms | {p95}ms | {p99}ms | {tps} | {error}% | {validation} |".format(
                label=current_label, threads=summaries[0]["threads"],
                rounds=len(summaries),
                avg=fmt_number(average(summaries, "average_ms")),
                median=fmt_number(average(summaries, "median_ms")),
                p90=fmt_number(average(summaries, "p90_ms")),
                p95=fmt_number(average(summaries, "p95_ms")),
                p99=fmt_number(average(summaries, "p99_ms")),
                tps=fmt_number(average(summaries, "tps")),
                error=fmt_number(average(summaries, "error_rate_pct"), 2),
                validation=validation,
            )
        )

    lines.extend(["", "## 每轮原始指标摘要", ""])
    for scene in ("A", "B"):
        summaries = by_scene[scene]
        if not summaries:
            continue
        lines.extend([
            f"### 场景 {scene}（{summaries[0]['threads']} 线程）",
            "",
            "| 轮次 | 样本 | 平均RT | 中位数 | P90 | P95 | P99 | 最小/最大RT | TPS | 异常率 | 入队/订单 | 最大积压 | 校验 |",
            "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|",
        ])
        for summary in summaries:
            lines.append(
                "| {round} | {samples} | {avg:.1f}ms | {median:.1f}ms | {p90:.0f}ms | "
                "{p95:.0f}ms | {p99:.0f}ms | {minimum:.0f}/{maximum:.0f}ms | {tps:.1f} | "
                "{error:.2f}% | {enqueue}/{orders} | {backlog} | {status} |".format(
                    round=summary["round"], samples=summary["samples"],
                    avg=summary["average_ms"], median=summary["median_ms"],
                    p90=summary["p90_ms"], p95=summary["p95_ms"], p99=summary["p99_ms"],
                    minimum=summary["min_ms"], maximum=summary["max_ms"], tps=summary["tps"],
                    error=summary["error_rate_pct"], enqueue=summary["enqueue_success"],
                    orders=summary["db_orders"], backlog=summary["max_observed_xlen"],
                    status="PASS" if summary["business_pass"] else "FAIL",
                )
            )
        lines.append("")

    lines.extend(["## 过程问题记录", ""])
    issue_lines = []
    for scene in ("A", "B"):
        for summary in by_scene[scene]:
            if summary["error_rate_pct"] > 0 or not summary["business_pass"]:
                issue_lines.append(
                    f"- 场景 {scene} 第 {summary['round']} 轮：异常率 "
                    f"{summary['error_rate_pct']:.2f}%，{summary['business_validation']}"
                )
    event_log = root / "events.log"
    if event_log.exists():
        for line in event_log.read_text(encoding="utf-8").splitlines():
            if " WARN " in line or " ERROR " in line:
                issue_lines.append(f"- {line}")
    lines.extend(issue_lines or [
        f"- 20 轮均未发现 HTTP/系统异常、超卖或漏单；{current_label} 均在超时窗口内排空。"
    ])

    lines.extend(["", "## 简短结论", ""])
    baselines = {"A": (1298.0, 460.4), "B": (107.0, 619.6)}
    stream_baselines = {"A": (265.8, 561.9, 1683.2), "B": (26.1, 78.9, 1865.4)}
    for scene in ("A", "B"):
        summaries = by_scene[scene]
        if not summaries:
            continue
        avg_rt = average(summaries, "average_ms")
        avg_tps = average(summaries, "tps")
        baseline_rt, baseline_tps = baselines[scene]
        lines.append(
            f"- {summaries[0]['threads']} 并发：相对同步 DB 历史基准，"
            f"{comparison_sentence('平均入队 RT ', avg_rt, baseline_rt, True)}，"
            f"{comparison_sentence('TPS ', avg_tps, baseline_tps, False)}。"
        )
        if is_rabbit:
            stream_rt, stream_p99, stream_tps = stream_baselines[scene]
            lines.append(
                f"- {summaries[0]['threads']} 并发：相对 Lua+Redis Stream 已验收均值，"
                f"{comparison_sentence('平均入队 RT ', avg_rt, stream_rt, True)}，"
                f"{comparison_sentence('P99 ', average(summaries, 'p99_ms'), stream_p99, True)}，"
                f"{comparison_sentence('TPS ', avg_tps, stream_tps, False)}。"
            )
    if all(summary["business_pass"] for summary in all_summaries):
        lines.append(
            f"- 接口指标是 Lua 预扣并获得 {current_label} 投递确认的入队 RT；异步消息排空后，确认成功数与数据库实际订单数逐轮一致。"
        )
    lines.extend([
        "",
        "说明：百分位采用 nearest-rank；TPS 按首个样本开始至最后一个样本结束的实际时间窗计算。",
        "",
    ])

    report_path = root / "benchmark-report.md"
    report_path.write_text("\n".join(lines), encoding="utf-8")
    print(report_path)


def build_parser():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    round_parser = subparsers.add_parser("round")
    round_parser.add_argument("--backend", choices=("stream", "rabbit"), default="stream")
    round_parser.add_argument("--jtl", required=True)
    round_parser.add_argument("--backlog", required=True)
    round_parser.add_argument("--output", required=True)
    round_parser.add_argument("--scene", required=True)
    round_parser.add_argument("--threads", type=int, required=True)
    round_parser.add_argument("--round", type=int, required=True)
    round_parser.add_argument("--initial-stock", type=int, required=True)
    round_parser.add_argument("--db-orders", type=int, required=True)
    round_parser.add_argument("--db-distinct-users", type=int, required=True)
    round_parser.add_argument("--db-stock", type=int, required=True)
    round_parser.add_argument("--redis-stock", type=int, required=True)
    round_parser.add_argument("--duplicate-users", type=int, required=True)
    round_parser.add_argument("--stream-xlen", type=int, required=True)
    round_parser.add_argument("--stream-pending", type=int, required=True)
    round_parser.add_argument("--dlq", type=int, default=0)
    round_parser.set_defaults(func=summarize_round)

    report_parser = subparsers.add_parser("report")
    report_parser.add_argument("--backend", choices=("stream", "rabbit"), default="stream")
    report_parser.add_argument("--results-dir", required=True)
    report_parser.set_defaults(func=write_report)
    return parser


if __name__ == "__main__":
    arguments = build_parser().parse_args()
    arguments.func(arguments)
