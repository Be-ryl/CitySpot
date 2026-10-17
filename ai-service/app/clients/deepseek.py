from openai import AsyncOpenAI, OpenAIError


class DeepSeekServiceError(RuntimeError):
    """Raised when DeepSeek is unavailable or is not configured."""


class DeepSeekClient:
    def __init__(self, api_key: str, base_url: str, model: str) -> None:
        self.model = model
        self.client = AsyncOpenAI(api_key=api_key, base_url=base_url) if api_key else None

    async def answer_policy_question(
        self,
        question: str,
        contexts: list[str],
    ) -> str:
        if self.client is None:
            raise DeepSeekServiceError("未配置 DEEPSEEK_API_KEY")

        context_text = "\n\n---\n\n".join(contexts)
        try:
            response = await self.client.chat.completions.create(
                model=self.model,
                temperature=0,
                messages=[
                    {
                        "role": "system",
                        "content": (
                            "你是黑马点评杭州站商户规则助手。只能依据提供的内部规则回答；"
                            "规则没有说明的内容必须明确说不知道，不得自行补充。"
                        ),
                    },
                    {
                        "role": "user",
                        "content": (
                            f"内部规则：\n{context_text}\n\n"
                            f"用户问题：{question}\n\n"
                            "请用简洁中文回答。"
                        ),
                    },
                ],
            )
        except OpenAIError as exc:
            raise DeepSeekServiceError("DeepSeek 调用失败") from exc

        answer = response.choices[0].message.content
        if not answer:
            raise DeepSeekServiceError("DeepSeek 返回了空答案")
        return answer
