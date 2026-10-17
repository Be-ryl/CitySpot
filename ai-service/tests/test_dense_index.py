import json
from pathlib import Path
import tempfile

import numpy as np
import pytest

from app.rag.dense_index import (
    QUERY_INSTRUCTION,
    DenseIndexError,
    DenseRagService,
    build_dense_index,
)


class FakeEmbedder:
    def __init__(self, dimension: int = 2) -> None:
        self.dimension = dimension
        self.calls: list[list[str]] = []

    def get_sentence_embedding_dimension(self) -> int:
        return self.dimension

    def encode(self, sentences, **kwargs):
        texts = list(sentences)
        self.calls.append(texts)
        vectors = []
        for text in texts:
            if self.dimension != 2:
                vectors.append([1.0] + [0.0] * (self.dimension - 1))
            elif "退款" in text or "到账" in text:
                vectors.append([3.0, 0.0])
            else:
                vectors.append([0.0, 2.0])
        return np.asarray(vectors, dtype=np.float32)


def build_fixture(root: Path):
    knowledge = root / "knowledge"
    knowledge.mkdir()
    (knowledge / "refund.md").write_text(
        "# 退款到账\n\n退款到账通常需要一个至三个工作日。\n",
        encoding="utf-8",
    )
    (knowledge / "invoice.md").write_text(
        "# 发票规则\n\n餐饮发票由实际提供服务的商户开具。\n",
        encoding="utf-8",
    )
    index = root / "index"
    build_embedder = FakeEmbedder()
    manifest = build_dense_index(
        knowledge,
        index,
        model_name="fake-model",
        embedder=build_embedder,
    )
    return knowledge, index, manifest, build_embedder


def test_index_contains_npy_chunks_and_manifest() -> None:
    with tempfile.TemporaryDirectory() as temporary_directory:
        _, index, manifest, build_embedder = build_fixture(Path(temporary_directory))
        embeddings = np.load(index / "embeddings.npy", allow_pickle=False)
        chunks = [
            json.loads(line)
            for line in (index / "chunks.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        index_files = {path.name for path in index.iterdir()}

    assert index_files == {"embeddings.npy", "chunks.jsonl", "manifest.json"}
    assert manifest["chunk_count"] == 2
    assert embeddings.shape == (2, 2)
    np.testing.assert_allclose(np.linalg.norm(embeddings, axis=1), 1.0)
    assert len(chunks) == 2
    assert len(build_embedder.calls) == 1


def test_dense_query_uses_instruction_without_reencoding_documents() -> None:
    with tempfile.TemporaryDirectory() as temporary_directory:
        knowledge, index, _, _ = build_fixture(Path(temporary_directory))
        embedder = FakeEmbedder()
        service = DenseRagService.from_index(
            index,
            model_name="fake-model",
            embedder=embedder,
            knowledge_directory=knowledge,
        )
        hits = service.search("退款多久到账", top_k=1)

    assert hits[0].source == "refund.md"
    assert embedder.calls == [[f"{QUERY_INSTRUCTION}退款多久到账"]]


def test_source_change_invalidates_existing_index() -> None:
    with tempfile.TemporaryDirectory() as temporary_directory:
        knowledge, index, _, _ = build_fixture(Path(temporary_directory))
        (knowledge / "refund.md").write_text("# 已变更\n\n新规则。", encoding="utf-8")

        with pytest.raises(DenseIndexError, match="source documents changed"):
            DenseRagService.from_index(
                index,
                model_name="fake-model",
                embedder=FakeEmbedder(),
                knowledge_directory=knowledge,
            )
