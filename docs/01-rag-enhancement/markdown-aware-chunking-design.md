# Markdown-aware Chunking Design

## 目标

优化 RAG 文档切分质量，让知识片段保留章节语义，并让引用溯源可以返回更清晰的“文件 + 章节 + 段落”。

## 现状问题

原实现使用固定的 `DocumentByParagraphSplitter(300, 100)`：

- chunk 参数写死在代码里，不方便按知识库规模调优。
- Markdown 标题层级没有进入 metadata。
- citation 只能返回文件名和段落编号，定位能力有限。
- 导入日志缺少 chunk 质量统计。

## 实现方案

### 1. 配置化切分参数

新增配置：

```yaml
rag:
  chunk:
    segment-size: 500
    segment-overlap: 80
    min-chars: 40
    chars-per-token: 2.0
```

对应环境变量：

```text
RAG_CHUNK_SEGMENT_SIZE
RAG_CHUNK_SEGMENT_OVERLAP
RAG_CHUNK_MIN_CHARS
RAG_CHUNK_CHARS_PER_TOKEN
```

### 2. Markdown 结构感知切分

导入 `.md` 文档时，先识别 Markdown 标题：

```text
# 一级标题
## 二级标题
### 三级标题
```

系统会维护标题路径：

```text
一级标题 > 二级标题 > 三级标题
```

然后在每个章节内部继续使用段落切分器，避免一个大章节直接塞进单个 chunk。

### 3. Chunk Metadata 增强

向 PgVector metadata 和 MySQL `rag_chunk` 表同步写入：

```text
section_title
heading_path
chunk_type
char_count
token_estimate
```

其中：

- `section_title`：当前 chunk 所属标题。
- `heading_path`：完整标题路径。
- `chunk_type`：例如 `markdown_section`、`paragraph`。
- `char_count`：chunk 字符数。
- `token_estimate`：按字符估算的 token 数。

### 4. Citation 增强

RAG 返回的 `citations` 新增：

```json
{
  "fileName": "xxx.md",
  "chunkIndex": 3,
  "sectionTitle": "动态知识植入",
  "headingPath": "RAG能力 > 动态知识植入"
}
```

模型提示词中的引用格式升级为：

```text
[1] xxx.md「RAG能力 > 动态知识植入」第3段
```

### 5. Chunk 质量日志

文档导入后打印：

```text
文档名
新增向量片段数
切分片段数
跳过无效片段数
平均长度
```

方便后续观察切分质量。

### 6. 切分策略变更重建

系统将文档内容与切分策略一起纳入 `content_hash`：

```text
document_text + chunk_profile
```

其中 `chunk_profile` 包含：

```text
markdown-aware-v1 / paragraph-v1
segment-size
segment-overlap
min-chars
```

当文档内容或切分策略变化时：

1. 查询当前 `doc_id` 的旧 `content_hash`。
2. 如果 hash 不一致，先删除 MySQL `rag_chunk` 中该文档的旧片段。
3. 同时调用 PgVector `EmbeddingStore.removeAll(embeddingIds)` 删除旧向量。
4. 再按新的切分策略重新写入业务表和向量库。

这样可以避免新旧切分结果同时存在，影响召回质量。

## 验收方式

### 1. 动态写入知识

```http
POST {{baseUrl}}/insert
Content-Type: application/json

{
  "question": "Markdown 结构感知切分是什么？",
  "answer": "Markdown 结构感知切分会先识别标题层级，再在章节内进行段落切分，并把 heading_path 写入 metadata。",
  "sourceName": "ChunkingTest.md"
}
```

### 2. RAG 引用测试

```http
POST {{baseUrl}}/rag/chat
Content-Type: application/json

{
  "userId": 1001,
  "sessionId": 94001,
  "prompt": "Markdown 结构感知切分有什么作用？"
}
```

预期：

- `citations[].sectionTitle` 或 `citations[].headingPath` 有值。
- answer 的引用区域包含章节路径。
- 日志能看到 chunk 切分统计。

## 简历表达

> 优化 RAG 文档切分策略，引入 Markdown 结构感知切分、章节级 metadata 标注与 chunk 质量统计，使 citation 支持文件、章节与段落级溯源，提升长文档检索可解释性与上下文定位能力。
