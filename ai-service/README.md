# HM Dianping AI Service

Python AI 服务，与原 Java 服务位于同一仓库、使用独立进程运行。

## 当前入口

- `GET /health`：健康检查。
- `POST /chat`：接收用户问题和可选经纬度；附近美食问题会调用 Java 商户接口。
- `GET /docs`：FastAPI 自动生成的接口说明。

请求示例：

```json
{
  "message": "附近有什么好吃的？",
  "location": {
    "longitude": 120.149192,
    "latitude": 30.316078
  }
}
```

响应中的 `trace` 会展示请求经过的步骤，后续工具调用也会记录在这里。

当前附近美食链路：

```text
POST /chat 的 longitude/latitude
  -> Python 附近商户工具
  -> GET Java /shop/of/type 的 x/y
  -> Redis GEO 返回附近商户
  -> Python 返回 answer、shops 和 trace
```

退款规则链路：

```text
退款问题
  -> 检索 knowledge/refund 中的内部规则
  -> 将命中的规则作为上下文交给 DeepSeek
  -> 返回 answer、sources 和 trace
```

当前规则库很小，因此先使用可检查的本地关键词检索，不引入向量数据库。文档规模扩大后再替换为向量检索。

## 本地启动

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --reload --port 8000
```

真实的 `DEEPSEEK_API_KEY` 只写入本地 `.env`，该文件已被 Git 忽略。
