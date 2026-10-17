import argparse
import json
from pathlib import Path

from app.evaluation.retrieval_eval import (
    RetrievalCase,
    build_report,
    evaluate_modes,
    load_cases,
    summarize,
    warmup_services,
)
from app.rag.service import RetrievalHit


POLICY_DIRECTORY = (
    Path(__file__).resolve().parents[1] / "knowledge" / "merchant_policy"
)
CASES_PATH = (
    Path(__file__).resolve().parents[1]
    / "evaluation"
    / "cases"
    / "merchant_policy_cases.jsonl"
)


class FakeService:
    def __init__(self, target_chunk_id: str) -> None:
        self.target_chunk_id = target_chunk_id
        self.queries: list[str] = []

    def search(self, query: str, top_k: int = 5) -> list[RetrievalHit]:
        self.queries.append(query)
        return [
            RetrievalHit(
                chunk_id=self.target_chunk_id,
                source="policy.md",
                content="evidence",
                score=1.0,
                rank=1,
                retrieved_by=["test"],
            )
        ]


def test_merchant_evaluation_has_12_topics_and_48_reviewed_queries() -> None:
    cases = load_cases(CASES_PATH, POLICY_DIRECTORY)

    assert len(cases) == 48
    assert len({case.topic_id for case in cases}) == 12
    assert {case.type for case in cases} == {"exact", "paraphrase", "mixed", "hard"}
    assert all(case.expected_chunk_id for case in cases)


def test_warmup_is_not_counted_as_scored_result() -> None:
    cases = [
        RetrievalCase("a", "a", "query-a", "policy.md", "evidence", "exact", "target"),
        RetrievalCase("b", "b", "query-b", "policy.md", "evidence", "exact", "target"),
    ]
    services = {mode: FakeService("target") for mode in ("bm25", "dense", "hybrid")}

    warmup_services(services, cases, top_k=3, warmup_query_count=1)
    results = evaluate_modes(services, cases, top_k=3, latency_runs=2)

    assert all(len(mode_results) == 2 for mode_results in results.values())
    assert all(
        service.queries == ["query-a", "query-a", "query-a", "query-b", "query-b"]
        for service in services.values()
    )


def test_report_records_reproduction_environment() -> None:
    case = RetrievalCase(
        "a", "a", "query-a", "policy.md", "evidence", "exact", "target"
    )
    services = {"bm25": FakeService("target")}
    results = evaluate_modes(services, [case], top_k=3)
    summaries = {mode: summarize(value) for mode, value in results.items()}
    args = argparse.Namespace(
        knowledge_dir=POLICY_DIRECTORY,
        cases=CASES_PATH,
        dense_index_dir=None,
        embedding_model="fake-model",
        top_k=3,
        candidate_top_k=20,
        rrf_k=60,
        warmup_query_count=5,
        latency_runs=5,
    )

    report = build_report(
        args=args,
        cases=[case],
        results_by_mode=results,
        summaries=summaries,
    )

    assert report["environment"]["python"]
    assert report["environment"]["platform"]
    assert report["warmup_query_count"] == 5
    assert report["latency_runs"] == 5
