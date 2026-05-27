package com.lou.infinitechatagent.controller;

import com.lou.infinitechatagent.common.BaseResponse;
import com.lou.infinitechatagent.common.ErrorCode;
import com.lou.infinitechatagent.common.ResultUtils;
import com.lou.infinitechatagent.exception.BusinessException;
import com.lou.infinitechatagent.rag.DocumentIngestionService;
import com.lou.infinitechatagent.rag.dto.DocumentIngestRequest;
import com.lou.infinitechatagent.rag.dto.DocumentIngestResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/rag/documents")
public class RagDocumentController {

    @Resource
    private DocumentIngestionService documentIngestionService;

    @PostMapping("/ingest")
    public BaseResponse<DocumentIngestResponse> ingest(@RequestBody DocumentIngestRequest request) {
        if (request == null || !StringUtils.hasText(request.getPath())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "path 不能为空");
        }
        Path path = Path.of(request.getPath()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "文档路径不存在: " + path);
        }
        int chunkCount = documentIngestionService.ingestDocumentsFromPath(path.toString());
        log.info("RAG - 接口触发文档入库完成，path={}，chunkCount={}", path, chunkCount);
        return ResultUtils.success(DocumentIngestResponse.builder()
                .path(path.toString())
                .chunkCount(chunkCount)
                .message(chunkCount > 0 ? "文档入库完成" : "未产生新增片段，可能已入库或文档无有效文本")
                .build());
    }
}
