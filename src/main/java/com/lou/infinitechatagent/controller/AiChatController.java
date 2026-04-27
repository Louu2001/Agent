package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.Monitor.MonitorContext;
import com.lou.infinitechatagent.Monitor.MonitorContextHolder;
import com.lou.infinitechatagent.ai.AiChat;
import com.lou.infinitechatagent.model.dto.ChatRequest;
import com.lou.infinitechatagent.model.dto.KnowledgeRequest;
import com.lou.infinitechatagent.rag.DocumentIngestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @ClassName AiChatController
 * @Description
 * @Author Lou
 * @Date 2026/4/12 8:30
 */


@Slf4j
@RestController
public class AiChatController {

    @Resource
    private AiChat aiChat;

//    @GetMapping("/chat")
//    public String chat(String sessionId, String prompt) {
//        return aiChat.chat(sessionId, prompt);
//    }

    @PostMapping("/chat")
    public String chat(@RequestBody ChatRequest chatRequest) {

        MonitorContextHolder.setContext(MonitorContext.builder().userId(chatRequest.getUserId()).sessionId(chatRequest.getSessionId()).build());
        String chat = aiChat.chat(chatRequest.getSessionId(), chatRequest.getPrompt());
        MonitorContextHolder.clearContext();
        return chat;
    }

//     @PostMapping("/streamChat")
//    public Flux<String> streamChat(@RequestBody ChatRequest chatRequest) {
//        return aiChat.streamChat(chatRequest.getSessionId(), chatRequest.getPrompt());
//    }
    @PostMapping("/streamChat")
    public Flux<String> streamChat(@RequestBody ChatRequest chatRequest) {
        MonitorContext context = MonitorContext.builder()
                .userId(chatRequest.getUserId())
                .sessionId(chatRequest.getSessionId())
                .build();

        return Flux.defer(() -> {
            MonitorContextHolder.setContext(context);
            return aiChat.streamChat(chatRequest.getSessionId(), chatRequest.getPrompt())
                    .doFinally(signal -> MonitorContextHolder.clearContext());
        });
    }


    @Resource
    private DocumentIngestionService documentIngestionService;


    @PostMapping("/insert")
    public String insertKnowledge(@RequestBody KnowledgeRequest knowledgeRequest) {
        try {
            int chunkCount = documentIngestionService.ingestQa(
                    knowledgeRequest.getQuestion(),
                    knowledgeRequest.getAnswer(),
                    knowledgeRequest.getSourceName()
            );
            log.info("RAG - 新增知识点成功: {}", knowledgeRequest.getQuestion());
            return "插入成功：已同步至文档、向量数据库和引用溯源表，共生成 " + chunkCount + " 个片段";
        } catch (Exception e) {
            log.error("RAG - 新增知识点失败", e);
            return "插入失败：" + e.getMessage();
        }
    }
}
