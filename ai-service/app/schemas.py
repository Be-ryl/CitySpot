from typing import Literal

from pydantic import BaseModel, Field


class Location(BaseModel):
    longitude: float = Field(ge=-180, le=180, description="经度")
    latitude: float = Field(ge=-85.05112878, le=85.05112878, description="纬度")


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)
    location: Location | None = None


class TraceStep(BaseModel):
    component: str
    action: str
    detail: str


class NearbyShop(BaseModel):
    id: int
    name: str
    address: str | None = None
    distance: float | None = None
    score: int | None = None
    avgPrice: int | None = None


class ChatResponse(BaseModel):
    answer: str
    route: Literal["pending", "nearby_food", "merchant_policy", "unsupported"]
    trace: list[TraceStep]
    shops: list[NearbyShop] = Field(default_factory=list)
    sources: list[str] = Field(default_factory=list)
