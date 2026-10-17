from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import Depends, FastAPI, Request

from app.clients.deepseek import DeepSeekClient, DeepSeekServiceError
from app.clients.hmdp import HmdpClient, HmdpServiceError
from app.config import Settings, get_settings
from app.rag.factory import RetrievalService, build_retrieval_service
from app.schemas import ChatRequest, ChatResponse, NearbyShop, TraceStep


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    """Load the configured index once and close Hybrid workers on shutdown."""
    settings = get_settings()
    retriever = build_retrieval_service(
        mode=settings.rag_retrieval_mode,
        knowledge_directory=str(settings.policy_knowledge_dir),
        dense_index_directory=str(settings.rag_dense_index_dir),
        embedding_model=settings.rag_embedding_model,
        candidate_top_k=settings.rag_candidate_top_k,
        rrf_k=settings.rag_rrf_k,
    )
    application.state.policy_retriever = retriever
    try:
        yield
    finally:
        close = getattr(retriever, "close", None)
        if callable(close):
            close()


app = FastAPI(
    title="HM Dianping AI Service",
    description="附近美食查询与商户规则 RAG 问答服务",
    version="0.2.0",
    lifespan=lifespan,
)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "hm-dianping-ai"}


def get_hmdp_client(settings: Settings = Depends(get_settings)) -> HmdpClient:
    return HmdpClient(settings.hmdp_base_url)


def get_deepseek_client(settings: Settings = Depends(get_settings)) -> DeepSeekClient:
    return DeepSeekClient(
        api_key=settings.deepseek_api_key,
        base_url=settings.deepseek_base_url,
        model=settings.deepseek_model,
    )


def get_policy_retriever(
    request: Request,
    settings: Settings = Depends(get_settings),
) -> RetrievalService:
    """Return the process-level retriever, with a lazy path for test clients."""
    retriever = getattr(request.app.state, "policy_retriever", None)
    if retriever is None:
        retriever = build_retrieval_service(
            mode=settings.rag_retrieval_mode,
            knowledge_directory=str(settings.policy_knowledge_dir),
            dense_index_directory=str(settings.rag_dense_index_dir),
            embedding_model=settings.rag_embedding_model,
            candidate_top_k=settings.rag_candidate_top_k,
            rrf_k=settings.rag_rrf_k,
        )
        request.app.state.policy_retriever = retriever
    return retriever


@app.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    hmdp_client: HmdpClient = Depends(get_hmdp_client),
    deepseek_client: DeepSeekClient = Depends(get_deepseek_client),
    policy_retriever: RetrievalService = Depends(get_policy_retriever),
) -> ChatResponse:
    trace = [
        TraceStep(
            component="api",
            action="receive_chat_request",
            detail=f"收到问题：{request.message}",
        )
    ]

    if is_policy_question(request.message):
        return await answer_policy_question(
            request,
            trace,
            policy_retriever,
            deepseek_client,
        )

    if not is_nearby_food_question(request.message):
        trace.append(
            TraceStep(
                component="router",
                action="unsupported",
                detail="当前规则未识别为附近美食问题",
            )
        )
        return ChatResponse(
            answer="当前支持附近美食查询，以及团购、退款、优惠券、预约和售后规则问答。",
            route="unsupported",
            trace=trace,
        )

    trace.append(
        TraceStep(
            component="router",
            action="select_nearby_food",
            detail="识别到附近美食问题，准备调用商户工具",
        )
    )
    if request.location is None:
        trace.append(
            TraceStep(
                component="tool",
                action="missing_location",
                detail="请求没有携带经纬度，未调用 Java 服务",
            )
        )
        return ChatResponse(
            answer="请先提供当前位置，我才能查询附近美食。",
            route="nearby_food",
            trace=trace,
        )

    trace.append(
        TraceStep(
            component="tool",
            action="call_search_nearby_food",
            detail=(
                "调用 Java /shop/of/type："
                f"typeId=1, x={request.location.longitude}, "
                f"y={request.location.latitude}"
            ),
        )
    )
    try:
        shops = await hmdp_client.search_nearby_food(request.location)
    except HmdpServiceError as exc:
        trace.append(
            TraceStep(
                component="tool",
                action="java_service_error",
                detail=str(exc),
            )
        )
        return ChatResponse(
            answer="商户服务暂时不可用，请稍后再试。",
            route="nearby_food",
            trace=trace,
        )

    trace.append(
        TraceStep(
            component="tool",
            action="receive_nearby_shops",
            detail=f"Java 服务返回 {len(shops)} 家附近商户",
        )
    )
    return ChatResponse(
        answer=format_nearby_shop_answer(shops),
        route="nearby_food",
        trace=trace,
        shops=shops,
    )


async def answer_policy_question(
    request: ChatRequest,
    trace: list[TraceStep],
    policy_retriever: RetrievalService,
    deepseek_client: DeepSeekClient,
) -> ChatResponse:
    trace.append(
        TraceStep(
            component="router",
            action="select_merchant_policy",
            detail="识别到商户规则问题，准备检索内部知识库",
        )
    )
    hits = policy_retriever.search(request.message, top_k=3)
    sources = list(dict.fromkeys(hit.source for hit in hits))
    route_details = ", ".join(
        f"{hit.chunk_id}[{'/'.join(hit.retrieved_by)}]" for hit in hits
    )
    trace.append(
        TraceStep(
            component="rag",
            action="retrieve_merchant_policy",
            detail=(
                f"检索到 {len(hits)} 个 Chunk：{route_details}"
                if hits
                else "没有检索到相关商户规则"
            ),
        )
    )
    if not hits:
        return ChatResponse(
            answer="内部知识库没有找到相关规则，请联系人工客服确认。",
            route="merchant_policy",
            trace=trace,
        )

    trace.append(
        TraceStep(
            component="llm",
            action="call_deepseek_with_context",
            detail="仅将命中的规则 Chunk 和用户问题发送给 DeepSeek",
        )
    )
    try:
        answer = await deepseek_client.answer_policy_question(
            question=request.message,
            contexts=[hit.content for hit in hits],
        )
    except DeepSeekServiceError as exc:
        trace.append(
            TraceStep(
                component="llm",
                action="deepseek_error",
                detail=str(exc),
            )
        )
        return ChatResponse(
            answer="商户规则已经找到，但大模型服务暂时不可用。",
            route="merchant_policy",
            trace=trace,
            sources=sources,
        )

    trace.append(
        TraceStep(
            component="llm",
            action="generate_grounded_answer",
            detail="DeepSeek 已依据召回的规则 Chunk 生成回答",
        )
    )
    return ChatResponse(
        answer=answer,
        route="merchant_policy",
        trace=trace,
        sources=sources,
    )


def is_nearby_food_question(message: str) -> bool:
    normalized = message.strip().lower()
    location_words = ("附近", "周边", "旁边", "nearby")
    food_words = ("吃", "美食", "餐厅", "饭店", "餐馆", "food")
    return any(word in normalized for word in location_words) and any(
        word in normalized for word in food_words
    )


def is_policy_question(message: str) -> bool:
    normalized = message.strip().lower()
    policy_words = (
        "退款",
        "退钱",
        "退券",
        "到账",
        "团购券",
        "优惠券",
        "代金券",
        "核销",
        "预约",
        "过期",
        "发票",
        "售后",
        "食品安全",
        "商家不接待",
        "商户不接待",
        "refund",
    )
    return any(word in normalized for word in policy_words)


def format_nearby_shop_answer(shops: list[NearbyShop]) -> str:
    if not shops:
        return "当前区域暂未开通服务，附近没有可用商户。"

    lines = ["为你找到这些附近美食："]
    for index, shop in enumerate(shops, start=1):
        details = []
        if shop.distance is not None:
            details.append(f"距离约 {shop.distance:.0f} 米")
        if shop.score is not None:
            details.append(f"评分 {shop.score / 10:.1f}")
        suffix = f"（{'，'.join(details)}）" if details else ""
        lines.append(f"{index}. {shop.name}{suffix}")
    return "\n".join(lines)
