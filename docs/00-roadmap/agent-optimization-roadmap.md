# 千言 Agent 下一阶段技术路线：ReAct + Adaptive RAG + Memory Agent

## 1. 目标定位

当前 InfiniteChat-Agent 已具备：

- 基础对话
- 流式输出
- Tool Calling
- Redis 会话记忆
- PgVector 向量检索
- MySQL 元数据表
- Hybrid Search
- RRF 融合
- Rule-based Rerank
- Citation 引用溯源
- Token Budget / Cost-aware RAG

下一阶段目标不是继续堆功能，而是升级 Agent 的“自主决策能力”：

```text
从：用户问题 -> 固定检索 -> 固定回答

升级为：
用户问题 -> ReAct 推理 -> 自主选择检索/工具/记忆/二次检索 -> 可解释回答
```

最终项目可以定位为：

```text
千言 · ReAct 驱动的自适应 RAG Agent 平台
```

## 2. 核心概念澄清

### 2.1 React 与 ReAct

| 名称 | 含义 | 是否本路线重点 |
| --- | --- | --- |
| React | 前端框架 | 不是当前重点 |
| ReAct | Reasoning + Acting，Agent 推理行动范式 | 是当前重点 |

ReAct 的核心是让模型在“思考”和“行动”之间循环：

```text
Thought -> Action -> Observation -> Thought -> Action -> Observation -> Final Answer
```

示例：

```text
用户：RAG-409 是什么错误？

Thought: 这是一个项目内部错误码问题，应先查询知识库。
Action: hybrid_search("RAG-409")
Observation: 命中错误码章节，包含原因和处理建议。
Thought: 证据充足，可以回答。
Final Answer: ...
```

## 3. 总体架构

```text
用户请求
  |
  v
AgentController
  |
  v
ReActAgentOrchestrator
  |
  |-- MemoryReader：读取短期记忆、摘要记忆、长期记忆
  |-- ReActLoop：Thought / Action / Observation 循环
  |-- ActionExecutor：执行 RAG、工具、记忆、联网搜索等动作
  |-- AnswerComposer：生成最终回答与引用
  |
  v
返回 AgentResponse
  |-- answer
  |-- citations
  |-- actionTrace
  |-- memoryTrace
  |-- retrievalTrace
  |-- tokenMetrics
```

## 4. 推荐优先顺序

### P0：保持现有 RAG 链路稳定

当前 Hybrid RAG 已经能作为 ReAct 的工具能力，不建议推倒重写。

保留：

- `VectorSearchService`
- `KeywordSearchService`
- `HybridSearchService`
- `RerankService`
- `RagQueryService`
- `Citation`
- `Token Budget`

下一阶段是在这些能力外面包一层 Agent 编排。

### P1：ReAct Agent 推理循环

优先级最高。

原因：

- 最贴近 Agent 项目核心。
- 面试最容易讲清楚。
- 能自然串起 RAG、工具调用、记忆和多轮检索。

目标：

```text
让 Agent 自主决定：
- 是否查知识库
- 是否调用工具
- 查哪类知识
- 是否二次检索
- 是否可以直接回答
```

### P2：Adaptive RAG

第二优先级。

它是 ReAct 中最重要的 Action 类型。

目标：

```text
LLM 决定检索策略：
- NO_RETRIEVAL
- VECTOR_ONLY
- KEYWORD_ONLY
- HYBRID
- MULTI_STEP
```

### P3：Memory Agent

第三优先级。

先做轻量版，不要一开始就复杂化。

目标：

- 会话摘要
- 用户偏好记忆
- 项目事实记忆
- 反思记忆

### P4：多 Agent 协作

第四优先级。

先不要做复杂多 Agent 框架，先做“角色拆分”：

- Router Agent
- Retrieval Agent
- Tool Agent
- Memory Agent

这些 Agent 可以先是 Java Service + Prompt，不一定需要独立进程。

### P5：多模态推理

第五优先级。

只有当你要处理图片、截图、PDF 扫描件、报错图片时再做。

否则会偏离当前项目主线。

## 5. 阶段一：ReAct Agent 推理循环

### 5.1 目标

新增 `/api/agent/chat`，让请求进入 ReAct 编排，而不是直接进入固定 RAG。

### 5.2 新增包结构

```text
com.lou.infinitechatagent.agent
  |-- ReActAgentOrchestrator.java
  |-- ReActPromptBuilder.java
  |-- ActionExecutor.java
  |-- ActionTraceRecorder.java
  |-- AgentController.java
  |
  |-- dto
      |-- AgentRequest.java
      |-- AgentResponse.java
      |-- ReActStep.java
      |-- AgentAction.java
      |-- AgentActionType.java
      |-- AgentObservation.java
```

### 5.3 Action 类型设计

```java
public enum AgentActionType {
    FINAL_ANSWER,
    NO_RETRIEVAL_ANSWER,
    VECTOR_SEARCH,
    KEYWORD_SEARCH,
    HYBRID_SEARCH,
    SECOND_RETRIEVAL,
    WEB_SEARCH,
    SEND_EMAIL,
    SAVE_KNOWLEDGE,
    READ_MEMORY,
    WRITE_MEMORY
}
```

### 5.4 ReAct Step 结构

```java
public class ReActStep {
    private Integer step;
    private String thought;
    private AgentAction action;
    private AgentObservation observation;
}
```

### 5.5 Agent Action 结构

```java
public class AgentAction {
    private AgentActionType type;
    private String query;
    private Map<String, Object> arguments;
}
```

### 5.6 ReAct Prompt

系统 Prompt：

```text
你是千言 Agent，必须按照 ReAct 格式进行推理。

每一步只能输出一个 JSON：
{
  "thought": "你对当前问题的分析",
  "action": {
    "type": "HYBRID_SEARCH",
    "query": "检索内容",
    "arguments": {}
  }
}

可用 action:
- NO_RETRIEVAL_ANSWER
- VECTOR_SEARCH
- KEYWORD_SEARCH
- HYBRID_SEARCH
- SECOND_RETRIEVAL
- WEB_SEARCH
- SEND_EMAIL
- SAVE_KNOWLEDGE
- READ_MEMORY
- WRITE_MEMORY
- FINAL_ANSWER

规则：
1. 内部项目知识、错误码、配置项、类名、流程问题，优先检索知识库。
2. 实时信息优先使用工具。
3. 如果 observation 证据不足，可以进行 SECOND_RETRIEVAL。
4. 如果证据足够，输出 FINAL_ANSWER。
5. 不得编造 citation 中不存在的来源。
```

### 5.7 ReAct Loop 伪代码

```java
for (int i = 0; i < maxSteps; i++) {
    AgentAction action = llm.decide(context, observations);

    if (action.type() == FINAL_ANSWER) {
        return composeFinalAnswer(action, traces);
    }

    AgentObservation observation = actionExecutor.execute(action);
    traces.add(new ReActStep(i, action, observation));
}

return fallbackAnswer(traces);
```

### 5.8 安全边界

高风险工具不直接执行：

- 删除数据
- 修改密码
- 发送邮件
- 修改权限

对于邮件这类动作：

```text
ReAct 可以生成草稿，但发送前需要确认。
```

### 5.9 验收标准

- `/api/agent/chat` 返回 `answer`
- 返回 `reactTrace`
- 能看到 Thought / Action / Observation
- 问 RAG-409 时自动选择 `HYBRID_SEARCH`
- 问普通闲聊时选择 `NO_RETRIEVAL_ANSWER`
- 检索不足时触发 `SECOND_RETRIEVAL`

### 5.10 简历写法

```text
基于 ReAct 范式实现 Agent 推理-行动循环，使模型在 Thought、Action、Observation、Final Answer 间迭代，自主决策是否检索知识库、调用工具或进行多轮补充检索，并记录完整 action trace，提升复杂任务处理能力与可解释性。
```

## 6. 阶段二：Adaptive RAG

### 6.1 目标

把现有 RAG 能力封装成 ReAct 可调用的 Action。

```text
HYBRID_SEARCH -> HybridSearchService
VECTOR_SEARCH -> VectorSearchService
KEYWORD_SEARCH -> KeywordSearchService
SECOND_RETRIEVAL -> query rewrite + hybrid search
```

### 6.2 检索策略

```java
public enum RetrievalStrategy {
    NO_RETRIEVAL,
    VECTOR_ONLY,
    KEYWORD_ONLY,
    HYBRID,
    MULTI_STEP
}
```

### 6.3 策略选择建议

| 用户问题 | 策略 |
| --- | --- |
| 你好、你是谁 | NO_RETRIEVAL |
| RAG-409、TOOL-403 | KEYWORD_ONLY / HYBRID |
| RagTool.addKnowledgeToRag | KEYWORD_ONLY / HYBRID |
| 动态知识植入是什么 | HYBRID |
| Redis 记忆和 RAG 区别 | HYBRID + RERANK |
| 今天新闻/天气 | WEB_SEARCH |
| 检索结果不足 | SECOND_RETRIEVAL |

### 6.4 证据充足性判断

可先用规则：

```text
top1.rerankScore >= 0.75
或 citations.size >= 2
或 top1.retrievalSource == hybrid
```

后续升级为 LLM 判断：

```json
{
  "sufficient": true,
  "reason": "引用片段已经包含定义、流程和处理方式"
}
```

### 6.5 多轮补充检索

流程：

```text
第一次检索：RAG-409
Observation: 命中错误码，但缺少处理建议
Thought: 需要补充检索处理建议
Action: SECOND_RETRIEVAL("RAG-409 处理建议")
Observation: 命中处理建议
Final Answer
```

### 6.6 验收标准

- 类名、配置项、错误码稳定命中
- 同一 chunk 不重复进入上下文
- 日志包含 vector、keyword、fused、rerank 前后数量
- 返回 retrievalStrategy 和 reactTrace

### 6.7 简历写法

```text
设计 Adaptive RAG 检索编排层，由 ReAct Agent 根据问题类型动态选择向量检索、关键词检索、混合检索或多轮补充检索，并结合 RRF、Rerank、Citation 和 Token Budget 控制，实现可解释、低成本、策略自适应的企业知识问答。
```

## 7. 阶段三：Memory Agent

### 7.1 目标

让 Agent 具备跨会话记忆和自我改进能力。

### 7.2 记忆类型

```text
SHORT_TERM：Redis 最近对话
SUMMARY：会话摘要
FACT：用户或项目事实
PREFERENCE：用户偏好
REFLECTION：反思记忆
```

### 7.3 表设计

#### memory_summary

```sql
create table memory_summary (
    id bigint primary key auto_increment,
    user_id bigint not null,
    session_id bigint not null,
    summary text not null,
    message_count int,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    key idx_memory_summary_user_session(user_id, session_id)
) engine=InnoDB default charset=utf8mb4;
```

#### memory_item

```sql
create table memory_item (
    id bigint primary key auto_increment,
    memory_id varchar(64) not null unique,
    user_id bigint not null,
    memory_type varchar(32) not null,
    content text not null,
    importance double default 0,
    source_session_id bigint,
    created_at timestamp default current_timestamp,
    updated_at timestamp default current_timestamp on update current_timestamp,
    key idx_memory_item_user_type(user_id, memory_type)
) engine=InnoDB default charset=utf8mb4;
```

### 7.4 Memory Agent 工作流

```text
请求开始
  |
  v
读取 Redis 最近对话
  |
  v
检索长期记忆
  |
  v
注入 ReAct Prompt
  |
  v
Agent 回答
  |
  v
判断是否需要写记忆
  |
  v
摘要 / 偏好 / 事实 / 反思 入库
```

### 7.5 反思记忆示例

```text
当用户要求提交代码前，应先检查 application.yml 是否包含真实密钥，并避免提交运行时测试文件。
```

### 7.6 验收标准

- 超过 N 轮对话自动生成摘要
- 用户明确“记住”时写入长期记忆
- 后续新会话能检索到长期记忆
- 响应里返回 memoryTrace

### 7.7 简历写法

```text
实现 Memory Agent 长短期记忆机制，基于 Redis 保存短期上下文，使用 MySQL + PgVector 存储会话摘要、用户偏好、项目事实和反思记忆，并在 ReAct 推理前动态召回相关记忆，实现跨会话个性化问答和 Agent 行为持续增强。
```

## 8. 阶段四：多 Agent 协作

### 8.1 建议边界

不要一开始做复杂分布式多 Agent。先做 Java Service 级别的角色拆分：

```text
Router Agent：判断任务类型和策略
Retrieval Agent：负责 RAG 检索
Tool Agent：负责工具执行
Memory Agent：负责记忆读写
Answer Agent：负责最终答案组织
```

### 8.2 协作流程

```text
ReActAgentOrchestrator
  -> Router Agent
  -> Retrieval Agent / Tool Agent / Memory Agent
  -> Answer Agent
```

### 8.3 简历写法

```text
采用多 Agent 角色拆分设计，将任务规划、知识检索、工具执行、记忆管理和答案生成解耦，构建可观测的 Agent 协作链路，提升复杂任务处理的可维护性和可扩展性。
```

## 9. 阶段五：多模态推理

### 9.1 建议落地方向

不要单纯“接一个视觉模型”，要和 RAG 结合：

- 上传报错截图
- 上传产品文档截图
- 上传 PDF 页面图片
- OCR / 多模态理解
- 结构化内容入知识库
- 基于识别结果问答

### 9.2 工作流

```text
图片上传
  |
  v
多模态模型 / OCR
  |
  v
抽取文本、表格、错误信息
  |
  v
写入临时上下文或知识库
  |
  v
ReAct Agent 判断是否检索/工具调用
```

### 9.3 简历写法

```text
扩展多模态推理能力，支持对截图、PDF 页面和报错图片进行视觉理解与 OCR 抽取，并将结构化结果接入 RAG 检索链路，实现图文混合场景下的智能问答。
```

## 10. 最终推荐实现顺序

```text
1. ReAct Agent 推理循环
2. Adaptive RAG 策略路由
3. Memory Agent
4. 多 Agent 角色拆分
5. 多模态推理
6. 前端可视化控制台
```

如果时间有限，只做前三个：

```text
ReAct + Adaptive RAG + Memory Agent
```

这三个已经足够把项目从“RAG 应用”提升为“Agent 系统”。

## 11. 最终简历项目描述

```text
千言 · ReAct 驱动的自适应 RAG Agent 平台

基于 Spring Boot + LangChain4j 构建企业级 Agent 系统，采用 ReAct 范式实现 Thought-Action-Observation 推理循环，使模型能够自主决策是否检索知识库、选择检索策略、调用外部工具或进行多轮补充检索。系统集成 Hybrid Search、RRF 融合、Rerank、Citation、Token Budget 和 Memory Agent，支持错误码、配置项、类名方法名等关键词问题的稳定命中，并通过长期记忆、对话摘要和反思记忆实现跨会话上下文增强。
```

