# BGE Rerank 本地配置

## 1. 需要准备什么

- Python 3.10+，或 Docker Desktop。
- 模型：`BAAI/bge-reranker-v2-m3`。
- Java 服务只需要访问一个 HTTP 接口：`POST http://localhost:8080/rerank`。
- 不需要百炼 API Key。

## 2. Python 启动方式

在项目根目录执行：

```powershell
python -m venv .venv-rerank
.\.venv-rerank\Scripts\activate
pip install -U fastapi uvicorn FlagEmbedding torch
python -m uvicorn scripts.bge_rerank_server:app --host 0.0.0.0 --port 8080
```

接口自测：

```powershell
$body = @{
  query = "什么是面向对象编程"
  texts = @(
    "面向对象编程是一种以对象为中心的编程方式。",
    "数据库索引用于提升查询性能。"
  )
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/rerank" `
  -ContentType "application/json" `
  -Body $body
```

预期返回：

```json
[
  {
    "index": 0,
    "score": 0.9
  },
  {
    "index": 1,
    "score": 0.1
  }
]
```

## 3. Java 服务配置

当前 `application.yml` 已默认使用 BGE：

```yaml
rag:
  rerank:
    enabled: true
    provider: bge
    endpoint: http://localhost:8080/rerank
    model: BAAI/bge-reranker-v2-m3
    request-format: tei
```

也可以用环境变量覆盖：

```powershell
$env:RAG_RERANK_PROVIDER = "bge"
$env:BGE_RERANK_ENDPOINT = "http://localhost:8080/rerank"
$env:RAG_RERANK_MODEL = "BAAI/bge-reranker-v2-m3"
$env:RAG_RERANK_REQUEST_FORMAT = "tei"
.\mvnw.cmd spring-boot:run
```

## 4. 常见现象

- 如果日志出现 `I/O error on POST request for "http://localhost:8080/rerank"`，说明本地 rerank 服务没启动或端口不对。
- Java 服务会自动降级为规则重排，不会影响 RAG 回答。
- 如果机器没有 GPU，Python 方式可以跑，但首次加载和推理会慢一些。
- 如果要临时关闭模型重排，设置 `RAG_RERANK_PROVIDER=rule` 或 `rag.rerank.enabled=false`。
