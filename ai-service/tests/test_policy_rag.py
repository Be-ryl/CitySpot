from fastapi.testclient import TestClient

from app.main import app, get_deepseek_client, get_policy_retriever
from app.rag.service import RetrievalHit


client = TestClient(app)


class FakeDeepSeekClient:
    def __init__(self) -> None:
        self.question: str | None = None
        self.contexts: list[str] = []

    async def answer_policy_question(
        self,
        question: str,
        contexts: list[str],
    ) -> str:
        self.question = question
        self.contexts = contexts
        return "支付宝退款通常在一个至三个工作日内原路到账。"


class FakeRetriever:
    def search(self, query: str, top_k: int = 5) -> list[RetrievalHit]:
        return [
            RetrievalHit(
                chunk_id="arrival-test",
                source="03_refund_processing_and_arrival.md",
                content="支付宝和微信支付通常在一个至三个工作日内原路到账。",
                score=1.0,
                rank=1,
                retrieved_by=["bm25", "dense"],
                bm25_rank=1,
                dense_rank=2,
                rrf_score=0.0325,
            )
        ][:top_k]


def test_policy_question_retrieves_chunks_before_calling_deepseek() -> None:
    fake_deepseek = FakeDeepSeekClient()
    app.dependency_overrides[get_deepseek_client] = lambda: fake_deepseek
    app.dependency_overrides[get_policy_retriever] = FakeRetriever
    try:
        response = client.post(
            "/chat",
            json={"message": "支付宝退款多久能到账？"},
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["route"] == "merchant_policy"
    assert body["sources"] == ["03_refund_processing_and_arrival.md"]
    assert fake_deepseek.question == "支付宝退款多久能到账？"
    assert any("一个至三个工作日" in text for text in fake_deepseek.contexts)
    assert [step["action"] for step in body["trace"]] == [
        "receive_chat_request",
        "select_merchant_policy",
        "retrieve_merchant_policy",
        "call_deepseek_with_context",
        "generate_grounded_answer",
    ]
    assert "arrival-test[bm25/dense]" in body["trace"][2]["detail"]
