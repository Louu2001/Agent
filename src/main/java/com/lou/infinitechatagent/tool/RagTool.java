package com.lou.infinitechatagent.tool;

import com.lou.infinitechatagent.rag.DocumentIngestionService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RagTool {

    @Resource
    private DocumentIngestionService documentIngestionService;

    /**
     * 定义工具方法。
     * 大模型会根据 @Tool 的描述和参数名来决定何时调用。
     */
    @Tool("当用户想要保存问答对、知识点或者向知识库添加新信息时调用此工具。将问题、答案和目标文件名作为参数。")
    public String addKnowledgeToRag(String question, String answer, String fileName) {
        log.info("Tool 调用: 正在保存知识 - Q: {}, file: {}", question, fileName);

        try {
            int chunkCount = documentIngestionService.ingestQa(question, answer, fileName);
            log.info("Tool 执行成功: 知识已同步至 RAG");
            return "成功！已将该知识点同步至知识库，并生成 " + chunkCount + " 个可溯源片段。";
        } catch (Exception e) {
            log.error("RAG - 知识写入失败", e);
            return "知识库写入失败：" + e.getMessage();
        }
    }
}
