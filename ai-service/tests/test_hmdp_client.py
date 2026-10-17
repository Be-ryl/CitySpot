import httpx
import pytest

from app.clients.hmdp import HmdpClient
from app.schemas import Location


@pytest.mark.anyio
async def test_search_nearby_food_maps_location_to_java_xy() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/shop/of/type"
        assert dict(request.url.params) == {
            "typeId": "1",
            "current": "1",
            "x": "120.149192",
            "y": "30.316078",
        }
        return httpx.Response(
            200,
            json={
                "success": True,
                "data": [
                    {
                        "id": 1,
                        "name": "103茶餐厅",
                        "distance": 128.6,
                        "score": 37,
                    }
                ],
            },
        )

    hmdp_client = HmdpClient(
        "http://java-service:8081",
        transport=httpx.MockTransport(handler),
    )

    shops = await hmdp_client.search_nearby_food(
        Location(longitude=120.149192, latitude=30.316078)
    )

    assert len(shops) == 1
    assert shops[0].name == "103茶餐厅"
    assert shops[0].distance == 128.6
