package com.lou.infinitechatagent.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class DocumentIngestionService {

    private static final int SEGMENT_SIZE = 300;

    private static final int SEGMENT_OVERLAP = 100;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @Value("${rag.docs-path}")
    private String docsPath;

    private final DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(SEGMENT_SIZE, SEGMENT_OVERLAP);

    public int ingestDocumentsFromPath(String docsPath) {
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(docsPath);
        int chunkCount = 0;
        for (Document document : documents) {
            chunkCount += ingestDocument(document, "local_file");
        }
        return chunkCount;
    }

    public int ingestQa(String question, String answer, String fileName) {
        String targetFileName = normalizeFileName(fileName);
        String content = String.format("### Q：%s%n%nA：%s", question, answer);
        appendToKnowledgeFile(targetFileName, content);
        Metadata metadata = Metadata.from("file_name", targetFileName);
        return ingestDocument(Document.from(content, metadata), "runtime_qa");
    }

    public int ingestDocument(Document document, String sourceType) {
        String fileName = resolveFileName(document);
        String filePath = resolveFilePath(document);
        String docId = "doc_" + sha256(fileName);
        String contentHash = sha256(document.text());

        ragJdbcTemplate.update("""
                insert into rag_document(doc_id, file_name, file_path, source_type, content_hash, updated_at)
                values (?, ?, ?, ?, ?, current_timestamp)
                on duplicate key update
                    file_name = values(file_name),
                    file_path = values(file_path),
                    source_type = values(source_type),
                    content_hash = values(content_hash),
                    updated_at = current_timestamp
                """, docId, fileName, filePath, sourceType, contentHash);

        List<TextSegment> splitSegments = splitter.split(document);
        List<String> ids = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();

        for (int i = 0; i < splitSegments.size(); i++) {
            TextSegment splitSegment = splitSegments.get(i);
            int chunkIndex = i + 1;
            String chunkText = splitSegment.text();
            String chunkId = "chunk_" + sha256(docId + "_" + chunkIndex + "_" + chunkText);
            String embeddingId = UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
            boolean existed = chunkExists(chunkId);
            Metadata metadata = splitSegment.metadata().copy()
                    .put("doc_id", docId)
                    .put("chunk_id", chunkId)
                    .put("file_name", fileName)
                    .put("chunk_index", chunkIndex);

            ragJdbcTemplate.update("""
                    insert into rag_chunk(chunk_id, doc_id, file_name, chunk_index, content, embedding_id)
                    values (?, ?, ?, ?, ?, ?)
                    on duplicate key update
                        doc_id = values(doc_id),
                        file_name = values(file_name),
                        chunk_index = values(chunk_index),
                        content = values(content),
                        embedding_id = values(embedding_id)
                    """, chunkId, docId, fileName, chunkIndex, chunkText, embeddingId);

            if (existed) {
                continue;
            }

            ids.add(embeddingId);
            segments.add(TextSegment.from(fileName + "\n" + chunkText, metadata));
        }

        if (segments.isEmpty()) {
            return 0;
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(ids, embeddings, segments);
        log.info("RAG - 文档 [{}] 已写入 {} 个可溯源片段", fileName, segments.size());
        return segments.size();
    }

    private boolean chunkExists(String chunkId) {
        Integer count = ragJdbcTemplate.queryForObject(
                "select count(1) from rag_chunk where chunk_id = ?",
                Integer.class,
                chunkId
        );
        return count != null && count > 0;
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "InfiniteChat.md";
        }
        return fileName.endsWith(".md") ? fileName : fileName + ".md";
    }

    private String resolveFileName(Document document) {
        String fileName = document.metadata().getString("file_name");
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        String absolutePath = document.metadata().getString("absolute_directory_path");
        if (absolutePath != null && !absolutePath.isBlank()) {
            return Path.of(absolutePath).getFileName().toString();
        }
        return "unknown.md";
    }

    private String resolveFilePath(Document document) {
        String filePath = document.metadata().getString("absolute_directory_path");
        return filePath == null ? "" : filePath;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("计算内容哈希失败", e);
        }
    }

    private synchronized void appendToKnowledgeFile(String fileName, String content) {
        try {
            Path filePath = Paths.get(docsPath, fileName);
            if (!Files.exists(filePath)) {
                if (filePath.getParent() != null) {
                    Files.createDirectories(filePath.getParent());
                }
                Files.createFile(filePath);
            }
            Files.writeString(filePath, "\n\n" + content, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("写入知识文档失败", e);
        }
    }
}
