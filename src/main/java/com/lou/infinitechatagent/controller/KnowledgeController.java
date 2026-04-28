package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.model.dto.KnowledgeRequest;
import com.lou.infinitechatagent.rag.DocumentIngestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class KnowledgeController {

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
