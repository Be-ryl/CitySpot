from threading import Barrier, get_ident

import pytest

from app.rag.hybrid import HybridRagService, HybridRetrievalError
from app.rag.service import RetrievalHit


def make_hit(chunk_id: str, route: str, rank: int) -> RetrievalHit:
    return RetrievalHit(
        chunk_id=chunk_id,
        source=f"{chunk_id}.md",
        content=f"{chunk_id} 的规则正文",
        score=float(10 - rank),
        rank=rank,
        retrieved_by=[route],
    )


class StaticService:
    def __init__(self, hits: list[RetrievalHit]) -> None:
        self.hits = hits
        self.calls: list[tuple[str, int]] = []

    def search(self, query: str, top_k: int = 5) -> list[RetrievalHit]:
        self.calls.append((query, top_k))
        return self.hits[:top_k]


class BarrierService(StaticService):
    def __init__(self, hits: list[RetrievalHit], barrier: Barrier) -> None:
        super().__init__(hits)
        self.barrier = barrier
        self.thread_ids: list[int] = []

    def search(self, query: str, top_k: int = 5) -> list[RetrievalHit]:
        self.thread_ids.append(get_ident())
        self.barrier.wait(timeout=1.0)
        return super().search(query, top_k)


class FailingService:
    def search(self, query: str, top_k: int = 5) -> list[RetrievalHit]:
        raise ValueError("route unavailable")


def test_bm25_and_dense_start_on_two_worker_threads() -> None:
    barrier = Barrier(2)
    bm25 = BarrierService([make_hit("bm25", "bm25", 1)], barrier)
    dense = BarrierService([make_hit("dense", "dense", 1)], barrier)
    service = HybridRagService(bm25, dense)
    try:
        results = service.search("并行召回", top_k=2)
    finally:
        service.close()

    assert {result.chunk_id for result in results} == {"bm25", "dense"}
    assert bm25.thread_ids[0] != dense.thread_ids[0]


def test_rrf_deduplicates_shared_chunks_and_keeps_route_ranks() -> None:
    bm25 = StaticService([
        make_hit("shared", "bm25", 1),
        make_hit("bm25-only", "bm25", 2),
    ])
    dense = StaticService([
        make_hit("shared", "dense", 1),
        make_hit("dense-only", "dense", 2),
    ])
    service = HybridRagService(bm25, dense)
    try:
        hits = service.search("退款规则", top_k=3)
    finally:
        service.close()

    assert [hit.chunk_id for hit in hits] == ["shared", "bm25-only", "dense-only"]
    assert hits[0].retrieved_by == ["bm25", "dense"]
    assert hits[0].bm25_rank == 1
    assert hits[0].dense_rank == 1
    assert hits[0].rrf_score == pytest.approx(2 / 61)
    assert bm25.calls == [("退款规则", 20)]
    assert dense.calls == [("退款规则", 20)]


def test_route_error_names_the_failed_route() -> None:
    service = HybridRagService(
        FailingService(),
        StaticService([make_hit("dense", "dense", 1)]),
    )
    try:
        with pytest.raises(HybridRetrievalError, match="BM25 retrieval failed"):
            service.search("异常检索")
    finally:
        service.close()
