from typing import Any

import httpx

from app.schemas import Location, NearbyShop


class HmdpServiceError(RuntimeError):
    """Raised when the Java business service cannot fulfill a request."""


class HmdpClient:
    def __init__(
        self,
        base_url: str,
        timeout_seconds: float = 3.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.transport = transport

    async def search_nearby_food(
        self,
        location: Location,
        current: int = 1,
    ) -> list[NearbyShop]:
        params = {
            "typeId": 1,
            "current": current,
            "x": location.longitude,
            "y": location.latitude,
        }
        try:
            async with httpx.AsyncClient(
                base_url=self.base_url,
                timeout=self.timeout_seconds,
                transport=self.transport,
            ) as client:
                response = await client.get("/shop/of/type", params=params)
                response.raise_for_status()
        except httpx.HTTPError as exc:
            raise HmdpServiceError("Java 商户服务暂时不可用") from exc

        payload: dict[str, Any] = response.json()
        if not payload.get("success"):
            error_message = payload.get("errorMsg") or "附近商户查询失败"
            raise HmdpServiceError(str(error_message))

        shop_data = payload.get("data") or []
        return [NearbyShop.model_validate(item) for item in shop_data]
