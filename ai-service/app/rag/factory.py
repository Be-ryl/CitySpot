"""Construct one configured retrieval service for the API process."""

from typing import Literal, Protocol

from app.rag.dense_index import DenseRagService
from app.rag.hybrid import HybridRagService
from app.rag.service import BM25RagService, RetrievalHit


RetrievalMode = Literal["bm25", "dense", "hybrid"]


class RetrievalService(Protocol):
    def search(self, query: str, top_k: int = 5) -> list[RetrievalHit]:
        """Return ranked policy chunks for one query."""


def build_retrieval_service(
    *,
    mode: RetrievalMode,
    knowledge_directory: str,
    dense_index_directory: str,
    embedding_model: str,
    candidate_top_k: int = 20,
    rrf_k: int = 60,
) -> RetrievalService:
    """Build BM25, Dense, or a two-route concurrent Hybrid retriever."""
    bm25_service = None
    dense_service = None

    if mode in {"bm25", "hybrid"}:
        bm25_service = BM25RagService.from_directory(knowledge_directory)
    if mode in {"dense", "hybrid"}:
        dense_service = DenseRagService.from_index(
            dense_index_directory,
            model_name=embedding_model,
            knowledge_directory=knowledge_directory,
        )

    if mode == "bm25" and bm25_service is not None:
        return bm25_service
    if mode == "dense" and dense_service is not None:
        return dense_service
    if mode == "hybrid" and bm25_service is not None and dense_service is not None:
        return HybridRagService(
            bm25_service,
            dense_service,
            candidate_top_k=candidate_top_k,
            rrf_k=rrf_k,
        )
    raise ValueError(f"unsupported retrieval mode: {mode}")
