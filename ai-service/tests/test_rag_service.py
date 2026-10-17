from pathlib import Path
import tempfile

from app.rag.service import BM25RagService, Document, chunk_document, load_documents


POLICY_DIRECTORY = (
    Path(__file__).resolve().parents[1] / "knowledge" / "merchant_policy"
)


def test_documents_load_in_stable_order_with_metadata() -> None:
    documents = load_documents(POLICY_DIRECTORY)

    assert len(documents) == 7
    assert documents[0].source == "01_voucher_purchase_and_redemption.md"
    assert documents[-1].source == "07_after_sales_food_safety_and_invoice.md"
    assert all(document.source_url for document in documents)
    assert all(document.version == "2026.10-demo-v1" for document in documents)


def test_chunk_ids_are_stable_and_headings_are_anchored_to_body() -> None:
    document = Document(
        document_id="refund-test",
        title="退款测试",
        source="refund_test.md",
        content="# 退款测试\n\n## 到账时间\n\n支付宝退款需要一个至三个工作日。",
    )

    first = chunk_document(document, max_chunk_chars=100)
    second = chunk_document(document, max_chunk_chars=100)

    assert first == second
    assert len(first) == 1
    assert "# 退款测试" in first[0].content
    assert "## 到账时间" in first[0].content
    assert first[0].chunk_id.startswith("refund-test-")


def test_every_paragraph_inherits_its_section_heading_path() -> None:
    document = Document(
        document_id="voucher-test",
        title="券规则",
        source="voucher_test.md",
        content=(
            "# 券规则\n\n## 退款\n\n第一段说明退款条件。\n\n"
            "第二段说明退款时效。\n\n## 核销\n\n第三段说明核销条件。"
        ),
    )

    chunks = chunk_document(document, max_chunk_chars=100)

    assert len(chunks) == 3
    assert all("# 券规则" in chunk.content for chunk in chunks)
    assert "## 退款" in chunks[0].content
    assert "## 退款" in chunks[1].content
    assert "## 核销" in chunks[2].content
    assert "## 退款" not in chunks[2].content
    assert all(len(chunk.content) <= 100 for chunk in chunks)


def test_changed_content_changes_stable_chunk_id() -> None:
    original = Document("policy", "规则", "policy.md", "# 规则\n\n原始内容。")
    changed = Document("policy", "规则", "policy.md", "# 规则\n\n修改后的内容。")

    assert chunk_document(original)[0].chunk_id != chunk_document(changed)[0].chunk_id


def test_empty_markdown_file_produces_no_searchable_chunks() -> None:
    with tempfile.TemporaryDirectory() as temporary_directory:
        path = Path(temporary_directory) / "empty.md"
        path.write_text("", encoding="utf-8")
        service = BM25RagService.from_directory(temporary_directory)

    assert service.chunks == []
    assert service.search("退款") == []


def test_bm25_finds_exact_refund_arrival_policy() -> None:
    service = BM25RagService.from_directory(POLICY_DIRECTORY)

    hits = service.search("支付宝和微信退款几个工作日到账", top_k=3)

    assert hits
    assert hits[0].source == "03_refund_processing_and_arrival.md"
    assert hits[0].retrieved_by == ["bm25"]
    assert "一个至三个工作日" in hits[0].content
