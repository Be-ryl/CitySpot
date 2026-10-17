from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    hmdp_base_url: str = "http://127.0.0.1:8081"
    rag_retrieval_mode: Literal["bm25", "dense", "hybrid"] = "bm25"
    policy_knowledge_dir: Path = (
        Path(__file__).resolve().parents[1] / "knowledge" / "merchant_policy"
    )
    rag_dense_index_dir: Path = (
        Path(__file__).resolve().parents[1]
        / "artifacts"
        / "indexes"
        / "merchant-policy-dense"
    )
    rag_embedding_model: str = str(
        Path(__file__).resolve().parents[1]
        / "models"
        / "bge-small-zh-v1.5"
    )
    rag_candidate_top_k: int = 20
    rag_rrf_k: int = 60

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
