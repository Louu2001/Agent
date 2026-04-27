# InfiniteChat-Agent RAG 增强技术方案

## 1. 背景与目标

当前 InfiniteChat-Agent 已经具备基于 LangChain4j、DashScope Qwen、text-embedding-v4 与 PgVector 的基础 RAG 能力，支持文档向量化、知识写入和语义检索。但现有实现主要依赖单一路径的向量召回，回答结果缺少明确引用来源，也没有对多路召回结果进行二次排序。

本方案目标是将现有 RAG 从“基础向量检索”升级为“可解释、可溯源、可优化”的企业级 RAG 检索引擎，核心增强包括：

- 引用溯源：回答时返回知识来源、片段内容、文件名、段落编号、相似度分数。
- 混合检索：结合向量语义检索与关键词全文检索，兼顾语义泛化能力和精确关键词命中。
- 重排序：对初召回结果进行二次排序，提升最终进入大模型上下文的片段质量。
- 检索可观测：记录每次 RAG 检索的召回数量、命中来源、分数分布、耗时和是否命中。

## 2. 当前项目基础

项目已有能力：

- `RagConfig`：配置 `EmbeddingStoreIngestor` 和 `ContentRetriever`。
- `EmbeddingStoreConfig`：使用 `PgVectorEmbeddingStore` 存储向量。
- `RagTool`：通过 `@Tool` 支持运行时动态知识写入。
- `AiChatService`：通过 LangChain4j `AiServices` 绑定 `contentRetriever`、Redis 记忆和工具调用。
- `AiChatController`：提供 `/chat`、`/streamChat`、`/insert` 接口。
- `AiModelMonitorListener`：已记录模型请求、响应耗时、Token 消耗等指标。

需要重点改造的点：

- 当前 `ContentRetriever` 只返回文本上下文，没有面向前端/API 的引用信息。
- PgVector 表结构由 LangChain4j 管理，不方便承载全文检索索引、段落编号、文档 ID 等业务字段。
- 当前 `maxResults=5`、`minScore=0.75` 是静态配置，缺少按场景调参能力。
- 向量召回和关键词召回没有融合，也没有 rerank 层。

## 3. 总体架构

增强后的 RAG 查询链路：

```text
用户问题
  |
  v
Query 预处理
  |-- 改写/清洗 query
  |-- 提取关键词
  |-- 生成 embedding
  |
  v
多路召回
  |-- 向量召回：PgVector cosine similarity
  |-- 关键词召回：PostgreSQL full-text search / BM25
  |
  v
结果融合
  |-- 去重
  |-- RRF 分数融合
  |-- metadata 合并
  |
  v
重排序
  |-- DashScope rerank / 本地 cross-encoder / 轻量规则重排
  |
  v
上下文组装
  |-- TopK 片段
  |-- 文件名、段落、score
  |-- token budget 控制
  |
  v
LLM 生成答案
  |
  v
返回答案 + 引用来源
```

推荐新增组件：

```text
com.lou.infinitechatagent.rag
  |-- DocumentIngestionService.java
  |-- HybridSearchService.java
  |-- VectorSearchService.java
  |-- KeywordSearchService.java
  |-- RerankService.java
  |-- CitationAssembler.java
  |-- RagQueryService.java
  |-- dto
      |-- RagQueryRequest.java
      |-- RagQueryResponse.java
      |-- Citation.java
      |-- RetrievedChunk.java
```

## 4. 数据模型设计

### 4.1 文档表

用于存储原始文档元信息。

```sql
create table rag_document (
    id bigserial primary key,
    doc_id varchar(64) not null unique,
    file_name varchar(255) not null,
    file_path varchar(512),
    source_type varchar(64),
    content_hash varchar(64),
    created_at timestamp default now(),
    updated_at timestamp default now()
);
```

### 4.2 文档片段表

用于存储切片文本、元数据、全文检索向量和向量索引关联字段。

```sql
create table rag_chunk (
    id bigserial primary key,
    chunk_id varchar(64) not null unique,
    doc_id varchar(64) not null,
    file_name varchar(255) not null,
    chunk_index int not null,
    title varchar(255),
    content text not null,
    metadata jsonb,
    content_tsv tsvector,
    created_at timestamp default now()
);

create index idx_rag_chunk_doc_id on rag_chunk(doc_id);
create index idx_rag_chunk_content_tsv on rag_chunk using gin(content_tsv);
```

如果继续使用 LangChain4j 的 `PgVectorEmbeddingStore`，可以在 metadata 中写入：

```json
{
  "doc_id": "doc_xxx",
  "chunk_id": "chunk_xxx",
  "file_name": "InfiniteChat.md",
  "chunk_index": 12,
  "source_type": "markdown"
}
```

更推荐的工程方案是：

- 向量仍由 LangChain4j `PgVectorEmbeddingStore` 管理。
- 业务元数据由 `rag_document` 和 `rag_chunk` 管理。
- 通过 `chunk_id` 将向量结果和业务片段表关联。

## 5. 引用溯源设计

### 5.1 Citation 返回结构

```java
public class Citation {
    private String docId;
    private String chunkId;
    private String fileName;
    private Integer chunkIndex;
    private String title;
    private String snippet;
    private Double vectorScore;
    private Double keywordScore;
    private Double rerankScore;
}
```

### 5.2 API 响应结构

```java
public class RagQueryResponse {
    private String answer;
    private List<Citation> citations;
    private Boolean hit;
    private Integer retrievedCount;
    private Long costMs;
}
```

### 5.3 Prompt 约束

系统提示词建议增加：

```text
你必须优先根据提供的知识片段回答问题。
如果知识片段不足以回答，请明确说明“当前知识库未提供足够信息”。
回答中可以引用来源编号，例如 [1]、[2]。
不得编造未出现在知识片段中的事实。
```

上下文拼接格式建议：

```text
[来源 1]
文件：InfiniteChat.md
段落：12
内容：xxx

[来源 2]
文件：Agent.md
段落：3
内容：xxx
```

这样模型生成答案时可以自然引用 `[1]`、`[2]`，前端也可以展示 citation 列表。

## 6. 混合检索设计

### 6.1 向量检索

向量检索负责语义泛化召回，适合以下问题：

- 用户表述和文档表述不完全一致。
- 问题较长、语义复杂。
- 需要跨句理解。

推荐参数：

```yaml
rag:
  vector:
    max-results: 20
    min-score: 0.60
```

实现方式：

```java
EmbeddingStoreContentRetriever.builder()
    .embeddingStore(embeddingStore)
    .embeddingModel(embeddingModel)
    .maxResults(20)
    .minScore(0.60)
    .build();
```

注意：最终不要直接把 20 个片段全部塞给模型，而是交给融合和重排序层。

### 6.2 关键词检索

关键词检索负责精确召回，适合以下问题：

- 查询中包含产品名、接口名、错误码、类名、配置项。
- 文档中有明确术语。
- 向量模型可能忽略短关键词。

PostgreSQL 全文检索示例：

```sql
select
    chunk_id,
    doc_id,
    file_name,
    chunk_index,
    content,
    ts_rank_cd(content_tsv, plainto_tsquery('simple', :query)) as keyword_score
from rag_chunk
where content_tsv @@ plainto_tsquery('simple', :query)
order by keyword_score desc
limit :limit;
```

中文场景注意：

- PostgreSQL 原生全文检索对中文分词支持较弱。
- 简历项目中可以写“关键词召回”，工程实现可以先用 `ILIKE`、trigram 或外部搜索引擎。
- 如果想做得更漂亮，可以引入 Elasticsearch / OpenSearch / Meilisearch 做 BM25。

轻量版关键词召回：

```sql
select *
from rag_chunk
where content ilike concat('%', :keyword, '%')
order by length(content) asc
limit :limit;
```

增强版关键词召回：

- PostgreSQL `pg_trgm` 模糊匹配。
- Elasticsearch BM25。
- OpenSearch BM25。
- Lucene 本地索引。

## 7. 结果融合设计

多路召回会出现重复片段，需要融合。

推荐使用 RRF，Reciprocal Rank Fusion。它不依赖不同检索器的原始分数尺度，适合融合向量检索和关键词检索。

RRF 公式：

```text
score = sum(1 / (k + rank_i))
```

其中：

- `rank_i` 是该片段在某一路召回中的排名。
- `k` 通常取 60。

融合流程：

```text
vectorResults: Top20
keywordResults: Top20
  |
  v
按 chunk_id 去重
  |
  v
计算 rrfScore
  |
  v
按 rrfScore desc 排序
  |
  v
取 Top20 进入 rerank
```

Java 伪代码：

```java
Map<String, RetrievedChunk> merged = new HashMap<>();

for (int i = 0; i < vectorResults.size(); i++) {
    RetrievedChunk chunk = vectorResults.get(i);
    chunk.addFusionScore(1.0 / (60 + i + 1));
    merged.merge(chunk.getChunkId(), chunk, RetrievedChunk::merge);
}

for (int i = 0; i < keywordResults.size(); i++) {
    RetrievedChunk chunk = keywordResults.get(i);
    chunk.addFusionScore(1.0 / (60 + i + 1));
    merged.merge(chunk.getChunkId(), chunk, RetrievedChunk::merge);
}

List<RetrievedChunk> fused = merged.values().stream()
    .sorted(Comparator.comparing(RetrievedChunk::getFusionScore).reversed())
    .limit(20)
    .toList();
```

## 8. 重排序设计

重排序负责从初召回结果中选出最适合进入上下文的片段。

### 8.1 方案 A：调用外部 Rerank 模型

可选模型：

- DashScope text-rerank 系列模型。
- BGE reranker。
- Cohere Rerank。

流程：

```text
query + candidate chunks
  |
  v
rerank model
  |
  v
rerank_score
  |
  v
TopK chunks
```

优点：

- 效果最好。
- 简历含金量高。

缺点：

- 增加一次模型调用成本。
- 增加延迟。

### 8.2 方案 B：轻量规则重排

如果暂时不接 rerank 模型，可以先做规则重排：

```text
finalScore =
  0.50 * fusionScore
  + 0.25 * vectorScore
  + 0.15 * keywordScore
  + 0.10 * freshnessScore
```

适合快速落地。

### 8.3 推荐落地策略

第一阶段先实现规则重排，确保工程闭环。

第二阶段再抽象 `RerankService` 接口：

```java
public interface RerankService {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
```

提供两个实现：

```text
RuleBasedRerankService
DashScopeRerankService
```

通过配置切换：

```yaml
rag:
  rerank:
    enabled: true
    provider: rule
    top-k: 5
```

## 9. 与 LangChain4j 的接入方式

### 9.1 方式一：替换 ContentRetriever

实现自定义 `ContentRetriever`：

```java
@Component
public class HybridContentRetriever implements ContentRetriever {

    private final RagQueryService ragQueryService;

    @Override
    public List<Content> retrieve(Query query) {
        List<RetrievedChunk> chunks = ragQueryService.retrieve(query.text());
        return chunks.stream()
            .map(chunk -> Content.from(chunk.toPromptText()))
            .toList();
    }
}
```

优点：

- 对现有 `AiChatService` 改动最小。
- 可以继续使用 LangChain4j 的 `AiServices`。

缺点：

- 引用信息需要额外保存在 ThreadLocal 或请求上下文中，才能返回给前端。

### 9.2 方式二：自建 RagQueryService 编排

流程：

```text
Controller
  -> RagQueryService.retrieveWithCitations()
  -> PromptAssembler
  -> ChatModel.chat()
  -> 返回 answer + citations
```

优点：

- 引用溯源最清晰。
- 方便记录检索指标。
- 前端/API 可直接拿 citation。

缺点：

- 改造比方式一更大。

推荐：

- 对现有 `/chat` 保持不变。
- 新增 `/rag/chat` 或 `/chatWithCitations`，使用方式二。
- 等稳定后再逐步替换原有 `contentRetriever`。

## 10. 接口设计

### 10.1 带引用的 RAG 问答接口

```http
POST /api/rag/chat
Content-Type: application/json
```

请求：

```json
{
  "userId": 1,
  "sessionId": 10001,
  "prompt": "系统如何实现动态知识植入？",
  "topK": 5,
  "withCitation": true
}
```

响应：

```json
{
  "answer": "系统通过 LangChain4j @Tool 将知识写入能力暴露给大模型...",
  "hit": true,
  "retrievedCount": 5,
  "costMs": 236,
  "citations": [
    {
      "docId": "doc_001",
      "chunkId": "chunk_001_12",
      "fileName": "InfiniteChat.md",
      "chunkIndex": 12,
      "snippet": "通过 @Tool 将 RAG 写入能力暴露给 LLM...",
      "vectorScore": 0.82,
      "keywordScore": 0.63,
      "rerankScore": 0.91
    }
  ]
}
```

### 10.2 文档摄取接口

```http
POST /api/rag/documents/ingest
```

请求：

```json
{
  "fileName": "Agent.md",
  "content": "文档内容",
  "sourceType": "markdown"
}
```

响应：

```json
{
  "docId": "doc_abc",
  "chunkCount": 18,
  "status": "success"
}
```

## 11. 实现步骤

### 阶段一：引用溯源

目标：让 RAG 回答能返回来源。

任务：

1. 新增 `rag_document`、`rag_chunk` 两张业务表。
2. 文档切分时生成 `doc_id`、`chunk_id`、`chunk_index`。
3. 向量 metadata 中写入 `doc_id`、`chunk_id`、`file_name`。
4. 新增 `Citation`、`RetrievedChunk`、`RagQueryResponse` DTO。
5. 新增 `/api/rag/chat`，返回 `answer + citations`。

验收标准：

- 用户问答后能看到至少一个引用来源。
- 引用中包含文件名、段落编号、片段摘要、相似度分数。
- 当没有召回结果时，模型明确提示知识库暂无相关信息。

### 阶段二：混合检索

目标：提升关键词类问题命中率。

任务：

1. 实现 `VectorSearchService`，封装现有 PgVector 检索。
2. 实现 `KeywordSearchService`，先用 PostgreSQL `ILIKE` 或 `pg_trgm`。
3. 实现 `HybridSearchService`，并行执行向量召回和关键词召回。
4. 使用 `chunk_id` 去重。
5. 使用 RRF 融合排序。

验收标准：

- 类名、配置项、错误码等关键词问题能够稳定命中。
- 同一片段不会重复进入上下文。
- 日志中能看到 vector、keyword 两路召回数量。

### 阶段三：重排序

目标：提升最终进入大模型上下文的片段质量。

任务：

1. 定义 `RerankService` 接口。
2. 实现 `RuleBasedRerankService`。
3. 支持配置 `rag.rerank.enabled`、`rag.rerank.top-k`。
4. 可选接入 DashScope Rerank。

验收标准：

- 初召回 Top20，重排序后 Top5 进入 Prompt。
- 返回 citation 中包含 `rerankScore`。
- 通过日志观察重排前后 TopK 变化。

### 阶段四：监控指标

目标：让 RAG 检索可观测。

新增指标：

```text
rag_retrieval_total
rag_retrieval_hit_total
rag_retrieval_miss_total
rag_retrieval_duration_seconds
rag_vector_results_count
rag_keyword_results_count
rag_rerank_duration_seconds
```

标签建议：

```text
user_id
session_id
retriever_type
hit
```

注意：

- 不建议把原始 query 放入 Prometheus tag，避免高基数和隐私风险。
- query 可以进入业务日志，但要做长度限制和敏感信息脱敏。

## 12. 关键代码改造点

### 12.1 RagConfig

当前：

```java
.maxResults(5)
.minScore(0.75)
```

建议：

- 改成配置项。
- 初召回提高到 20。
- 最终 TopK 由 rerank 控制为 5。

```yaml
rag:
  vector:
    max-results: 20
    min-score: 0.60
  final-top-k: 5
```

### 12.2 EmbeddingStoreConfig

当前：

```java
.dropTableFirst(true)
```

建议改为：

```java
.dropTableFirst(false)
```

否则服务重启可能清空向量表，生产环境风险极高。

### 12.3 RagTool

当前 `RagTool` 直接把 Q&A 写入文件和向量库。

建议改造为调用统一的 `DocumentIngestionService`：

```text
RagTool
  -> DocumentIngestionService.ingestQa(question, answer, fileName)
  -> 写 rag_document / rag_chunk
  -> 写 PgVector
```

这样普通文档导入和 Agent 动态知识写入可以共用同一套切分、metadata、向量化逻辑。

## 13. 简历写法

可以写成：

> 对 RAG 检索链路进行增强，设计“向量召回 + 关键词召回 + RRF 融合 + Rerank 重排序”的混合检索架构，并在回答结果中返回文件名、段落编号、相似度分数等引用来源，提升企业知识问答的准确性与可解释性。

或者更偏工程：

> 基于 PgVector 与 PostgreSQL 全文检索实现混合检索，使用 RRF 融合多路召回结果，并引入可插拔 RerankService 对候选片段二次排序；同时构建 Citation 机制返回答案来源，实现 RAG 问答可追溯、可观测和可调优。

如果面试官追问，可以按下面回答：

```text
原来只做向量召回，遇到类名、接口名、配置项这类关键词问题时可能命中不稳定。
我把检索拆成三层：第一层向量召回负责语义相似，第二层关键词召回负责精确匹配，第三层用 RRF 做结果融合，再通过 RerankService 选出 TopK 片段进入 Prompt。
同时每个 chunk 都带 doc_id、chunk_id、file_name、chunk_index 等 metadata，所以模型回答后可以返回引用来源，方便用户验证答案。
```

## 14. 推荐优先级

最推荐按这个顺序做：

1. 先做引用溯源，因为最容易展示效果。
2. 再做混合检索，因为能明显提升召回。
3. 再做规则重排序，因为成本低、实现快。
4. 最后接真实 rerank 模型，因为它涉及额外成本和延迟优化。

## 15. 一句话总结

这次增强的核心不是“再加一个检索接口”，而是把 RAG 从黑盒式上下文拼接升级为一条可解释、可追踪、可评估、可持续优化的企业级检索链路。
