# InfiniteChat Agent Documentation

本目录用于归档 Agent 项目的技术路线、核心实现文档与 Postman 测试集合。

## 目录结构

```text
docs/
├── 00-roadmap/
├── 01-rag-enhancement/
│   └── postman/
├── 02-react-agent/
│   └── postman/
├── 03-adaptive-rag/
│   └── postman/
├── 04-memory-agent/
│   └── postman/
├── 05-tool-governance/
│   └── postman/
└── 06-input-guardrail/
    └── postman/
```

## 文档索引

### 00 Roadmap

- [Agent Optimization Roadmap](./00-roadmap/agent-optimization-roadmap.md)

### 01 RAG Enhancement

- [RAG Citation, Hybrid Search and Rerank Design](./01-rag-enhancement/rag-citation-hybrid-rerank-design.md)
- [Cost-aware RAG Token Optimization](./01-rag-enhancement/cost-aware-rag-token-optimization.md)
- [Markdown-aware Chunking Design](./01-rag-enhancement/markdown-aware-chunking-design.md)
- [RAG Citation Postman Collection](./01-rag-enhancement/postman/rag-citation.postman_collection.json)
- [Hybrid RAG Rerank Postman Collection](./01-rag-enhancement/postman/hybrid-rag-rerank.postman_collection.json)

### 02 ReAct Agent

- [ReAct Agent Implementation](./02-react-agent/react-agent-implementation.md)
- [ReAct Agent Postman Collection](./02-react-agent/postman/react-agent.postman_collection.json)

### 03 Adaptive RAG

- [Adaptive RAG Technical Design](./03-adaptive-rag/adaptive-rag-technical-design.md)
- [Adaptive RAG Postman Collection](./03-adaptive-rag/postman/adaptive-rag.postman_collection.json)

### 04 Memory Agent

- [Memory Agent Roadmap](./04-memory-agent/memory-agent-roadmap.md)
- [Memory Agent Technical Design](./04-memory-agent/memory-agent-technical-design.md)
- [Memory Dedup Correction Design](./04-memory-agent/memory-dedup-correction-design.md)
- [Memory Agent Postman Collection](./04-memory-agent/postman/memory-agent.postman_collection.json)
- [Memory Dedup Correction Postman Collection](./04-memory-agent/postman/memory-dedup-correction.postman_collection.json)

### 05 Tool Governance

- [Tool Governance Technical Design](./05-tool-governance/tool-governance-technical-design.md)
- [Tool Governance Postman Collection](./05-tool-governance/postman/tool-governance.postman_collection.json)

### 06 Input Guardrail

- [Safe Input Guardrail Technical Design](./06-input-guardrail/safe-input-guardrail-technical-design.md)
- [Safe Input Guardrail Postman Collection](./06-input-guardrail/postman/safe-input-guardrail.postman_collection.json)

## 收录原则

- 每个能力模块只保留总体路线、完整实现或最终测试集合。
- 已被完整文档覆盖的小阶段文档不再重复收录。
- Postman 集合按模块放入对应 `postman/` 子目录。
