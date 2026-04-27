package com.lou.infinitechatagent.job;

import com.lou.infinitechatagent.rag.DocumentIngestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RagDataLoader implements CommandLineRunner {

    @Value("${rag.docs-path}")
    private String docsPath;

    @Resource
    private DocumentIngestionService documentIngestionService;

    @Override
    public void run(String... args) {
        log.info("RAG - 开始加载本地基础文档，路径: {}", docsPath);
        try {
            int chunkCount = documentIngestionService.ingestDocumentsFromPath(docsPath);
            if (chunkCount > 0) {
                log.info("RAG - 本地文档加载完成，共写入 {} 个可溯源片段", chunkCount);
            } else {
                log.warn("RAG - 指定路径下未发现新文档片段");
            }
        } catch (Exception e) {
            log.error("RAG - 加载本地文档失败", e);
        }
    }
}
