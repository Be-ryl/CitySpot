from fastapi.testclient import TestClient

from app.main import app, get_hmdp_client
from app.schemas import Location, NearbyShop


client = TestClient(app)


class FakeHmdpClient:
    def __init__(self) -> None:
        self.received_location: Location | None = None

    async def search_nearby_food(self, location: Location) -> list[NearbyShop]:
        self.received_location = location
        return [
            NearbyShop(
                id=1,
                name="103茶餐厅",
                address="金华路锦昌文华苑29号",
                distance=128.6,
                score=37,
                avgPrice=80,
            )
        ]


def test_health() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "hm-dianping-ai",
    }


def test_chat_calls_nearby_food_tool_with_location() -> None:
    fake_client = FakeHmdpClient()
    app.dependency_overrides[get_hmdp_client] = lambda: fake_client
    try:
        response = client.post(
            "/chat",
            json={
                "message": "附近有什么好吃的？",
                "location": {
                    "longitude": 120.149192,
                    "latitude": 30.316078,
                },
            },
        )
    finally:
        app.dependency_overrides.clear()

    assert response.status_code == 200
    body = response.json()
    assert body["route"] == "nearby_food"
    assert body["shops"][0]["name"] == "103茶餐厅"
    assert "距离约 129 米" in body["answer"]
    assert [step["action"] for step in body["trace"]] == [
        "receive_chat_request",
        "select_nearby_food",
        "call_search_nearby_food",
        "receive_nearby_shops",
    ]
    assert fake_client.received_location == Location(
        longitude=120.149192,
        latitude=30.316078,
    )


def test_nearby_food_without_location_asks_for_location() -> None:
    response = client.post(
        "/chat",
        json={"message": "附近有什么好吃的？"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["route"] == "nearby_food"
    assert body["shops"] == []
    assert body["trace"][-1]["action"] == "missing_location"
