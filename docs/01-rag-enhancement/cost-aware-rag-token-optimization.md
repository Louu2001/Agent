# Token 优化方案：Cost-aware RAG

## 1. 优化目标

RAG 问答的主要成本和耗时通常不在向量检索，而在最终大模型生成阶段。上下文越长，输入 token 越多，模型延迟和调用成本越高。

本方案目标：

- 自动控制上下文长度。
- 对 chunk 进行动态裁剪。
- 在 rerank 后执行截断策略。
- 在响应中返回 token 和耗时估算指标，便于观察成本。

## 2. 当前实现链路

```text
用户问题
  -> Embedding
  -> PgVector 召回 candidate-results
  -> RuleBasedRerankService 重排序
  -> 取 final-top-k
  -> applyTokenBudget 动态裁剪
  -> 组装 Prompt
  -> ChatModel 生成
  -> 返回 answer + citations + cost metrics
```

## 3. 配置项

```yaml
rag:
  retrieval:
    candidate-results: 8
  citation:
    final-top-k: 3
    min-score: 0.75
    snippet-max-chars: 500
    max-output-tokens: 500
  token:
    max-input-tokens: 1800
    reserved-system-tokens: 300
    min-chunk-chars: 180
    chars-per-token: 2.0
```

含义：

- `candidate-results`：初召回候选数量，给 rerank 留空间。
- `final-top-k`：最终进入 Prompt 的片段数量。
- `snippet-max-chars`：单个片段的预裁剪上限。
- `max-output-tokens`：模型最大输出 token。
- `max-input-tokens`：输入 token 预算。
- `reserved-system-tokens`：为 system prompt 和模板保留的 token。
- `min-chunk-chars`：每个 chunk 至少保留的字符数，避免裁得太碎。
- `chars-per-token`：粗略 token 估算系数，中文场景可先按 2 字符/token 估算。

## 4. 策略说明

### 4.1 自动控制上下文长度

系统会根据 `max-input-tokens` 计算可用字符预算：

```text
promptBudgetChars = (maxInputTokens - reservedSystemTokens) * charsPerToken
contextBudgetChars = promptBudgetChars - fixedPromptChars
```

如果 rerank 后的所有 chunk 总长度没有超过预算，则不裁剪。

如果超过预算，则进入动态裁剪。

### 4.2 chunk 动态裁剪

系统会按最终进入 Prompt 的 chunk 数量平均分配预算：

```text
perChunkBudget = contextBudgetChars / finalChunkCount
```

裁剪时优先在句号、换行、分号处截断，尽量不破坏语义。

### 4.3 rerank + 截断策略

先 rerank，再截断。

原因：

- rerank 前裁剪可能误伤高价值片段。
- rerank 后只处理 TopK，可以降低截断复杂度。
- 高相关片段会优先保留，低相关片段直接被淘汰。

### 4.4 cost-aware 响应指标

`/api/rag/chat` 响应新增：

```json
{
  "candidateCount": 8,
  "retrievedCount": 3,
  "promptChars": 1800,
  "contextChars": 1200,
  "estimatedInputTokens": 900,
  "contextTruncated": true,
  "retrievalCostMs": 600,
  "modelCostMs": 4500,
  "costMs": 5100
}
```

用途：

- `candidateCount`：判断召回规模是否过大。
- `retrievedCount`：判断最终上下文片段数量。
- `promptChars`：判断 Prompt 是否过长。
- `contextChars`：判断知识片段占用。
- `estimatedInputTokens`：粗略估算输入 token。
- `contextTruncated`：判断是否触发预算裁剪。
- `modelCostMs`：判断瓶颈是否在模型生成。

## 5. 面试表达

可以这样讲：

```text
我在 RAG 链路里做了 cost-aware 优化。系统不是简单把 TopK 文档全部塞进 Prompt，而是先扩大候选召回，然后通过 rerank 选出高相关片段，再根据输入 token 预算动态裁剪 chunk。
同时响应中会返回 promptChars、estimatedInputTokens、contextTruncated、modelCostMs 等指标，用于观察模型调用成本和耗时。
这样既能保证引用片段质量，又能控制上下文长度，降低大模型调用延迟和 token 成本。
```

