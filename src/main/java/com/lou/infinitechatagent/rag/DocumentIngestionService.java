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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DocumentIngestionService {

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private JdbcTemplate ragJdbcTemplate;

    @Value("${rag.docs-path}")
    private String docsPath;

    @Value("${rag.chunk.segment-size:500}")
    private int segmentSize;

    @Value("${rag.chunk.segment-overlap:80}")
    private int segmentOverlap;

    @Value("${rag.chunk.min-chars:40}")
    private int minChunkChars;

    @Value("${rag.chunk.chars-per-token:2.0}")
    private double charsPerToken;

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
        String contentHash = sha256(document.text() + "\nchunk_profile:" + chunkProfile(fileName));
        Optional<String> previousContentHash = findDocumentContentHash(docId);
        if (previousContentHash.isPresent() && !previousContentHash.get().equals(contentHash)) {
            purgeDocumentChunks(docId);
        }

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

        List<ChunkCandidate> splitSegments = splitDocument(document, fileName);
        List<String> ids = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        int skippedCount = 0;

        for (int i = 0; i < splitSegments.size(); i++) {
            ChunkCandidate splitSegment = splitSegments.get(i);
            int chunkIndex = i + 1;
            String chunkText = splitSegment.text();
            if (!isValidChunk(chunkText)) {
                skippedCount++;
                continue;
            }
            String chunkId = "chunk_" + sha256(docId + "_" + chunkIndex + "_" + chunkText);
            String embeddingId = UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
            boolean existed = chunkExists(chunkId);
            int charCount = chunkText.length();
            int tokenEstimate = estimateTokens(chunkText);
            Metadata metadata = enrichMetadata(document.metadata().copy(),
                    splitSegment,
                    docId,
                    chunkId,
                    fileName,
                    chunkIndex,
                    charCount,
                    tokenEstimate);

            ragJdbcTemplate.update("""
                    insert into rag_chunk(
                        chunk_id, doc_id, file_name, chunk_index,
                        section_title, heading_path, chunk_type, char_count, token_estimate,
                        content, embedding_id
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on duplicate key update
                        doc_id = values(doc_id),
                        file_name = values(file_name),
                        chunk_index = values(chunk_index),
                        section_title = values(section_title),
                        heading_path = values(heading_path),
                        chunk_type = values(chunk_type),
                        char_count = values(char_count),
                        token_estimate = values(token_estimate),
                        content = values(content),
                        embedding_id = values(embedding_id)
                    """,
                    chunkId,
                    docId,
                    fileName,
                    chunkIndex,
                    splitSegment.sectionTitle(),
                    splitSegment.headingPath(),
                    splitSegment.chunkType(),
                    charCount,
                    tokenEstimate,
                    chunkText,
                    embeddingId);

            if (existed) {
                continue;
            }

            ids.add(embeddingId);
            segments.add(TextSegment.from(fileName + "\n" + chunkText, metadata));
        }

        if (segments.isEmpty()) {
            log.info("RAG - 文档 [{}] 无新增向量片段，切分片段={}，跳过无效片段={}", fileName, splitSegments.size(), skippedCount);
            return 0;
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(ids, embeddings, segments);
        log.info("RAG - 文档 [{}] 已写入 {} 个可溯源片段，切分片段={}，跳过无效片段={}，平均长度={}字符",
                fileName,
                segments.size(),
                splitSegments.size(),
                skippedCount,
                averageChars(segments));
        return segments.size();
    }

    private String chunkProfile(String fileName) {
        return String.join("|",
                isMarkdown(fileName) ? "markdown-aware-v1" : "paragraph-v1",
                "size=" + segmentSize,
                "overlap=" + segmentOverlap,
                "min=" + minChunkChars);
    }

    private List<ChunkCandidate> splitDocument(Document document, String fileName) {
        if (isMarkdown(fileName)) {
            return splitMarkdown(document.text());
        }
        return splitPlainText(document.text(), null, null, "paragraph");
    }

    private List<ChunkCandidate> splitMarkdown(String text) {
        List<MarkdownSection> sections = parseMarkdownSections(text);
        if (sections.isEmpty()) {
            return splitPlainText(text, null, null, "markdown");
        }
        List<ChunkCandidate> chunks = new ArrayList<>();
        for (MarkdownSection section : sections) {
            chunks.addAll(splitPlainText(section.content(), section.sectionTitle(), section.headingPath(), "markdown_section"));
        }
        return chunks;
    }

    private List<MarkdownSection> parseMarkdownSections(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<MarkdownSection> sections = new ArrayList<>();
        String[] headings = new String[6];
        String currentTitle = null;
        String currentHeadingPath = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : text.split("\\R")) {
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(line.strip());
            if (matcher.matches()) {
                flushSection(sections, currentTitle, currentHeadingPath, currentContent);
                int level = matcher.group(1).length();
                String title = matcher.group(2).strip();
                headings[level - 1] = title;
                Arrays.fill(headings, level, headings.length, null);
                currentTitle = title;
                currentHeadingPath = buildHeadingPath(headings);
                currentContent = new StringBuilder(line).append('\n');
            } else {
                currentContent.append(line).append('\n');
            }
        }
        flushSection(sections, currentTitle, currentHeadingPath, currentContent);
        return sections;
    }

    private void flushSection(List<MarkdownSection> sections, String title, String headingPath, StringBuilder content) {
        if (content == null || !isValidChunk(content.toString())) {
            return;
        }
        sections.add(new MarkdownSection(title, headingPath, content.toString().strip()));
    }

    private String buildHeadingPath(String[] headings) {
        return Arrays.stream(headings)
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " > " + right)
                .orElse(null);
    }

    private List<ChunkCandidate> splitPlainText(String text, String sectionTitle, String headingPath, String chunkType) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Document document = Document.from(text);
        DocumentByParagraphSplitter splitter = new DocumentByParagraphSplitter(segmentSize, segmentOverlap);
        return splitter.split(document).stream()
                .map(segment -> new ChunkCandidate(
                        segment.text().strip(),
                        normalizeBlank(sectionTitle),
                        normalizeBlank(headingPath),
                        chunkType
                ))
                .filter(chunk -> isValidChunk(chunk.text()))
                .toList();
    }

    private boolean isValidChunk(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.strip();
        if (normalized.length() < minChunkChars && !normalized.contains("：") && !normalized.contains(":")) {
            return false;
        }
        return !normalized.matches("(?s)^#{1,6}\\s+[^\\n]+$");
    }

    private boolean isMarkdown(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".md");
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / charsPerToken);
    }

    private int averageChars(List<TextSegment> segments) {
        return (int) segments.stream()
                .map(TextSegment::text)
                .map(this::stripEmbeddingPrefix)
                .mapToInt(String::length)
                .average()
                .orElse(0);
    }

    private String stripEmbeddingPrefix(String text) {
        if (text == null) {
            return "";
        }
        int lineBreak = text.indexOf('\n');
        return lineBreak >= 0 ? text.substring(lineBreak + 1) : text;
    }

    private boolean chunkExists(String chunkId) {
        Integer count = ragJdbcTemplate.queryForObject(
                "select count(1) from rag_chunk where chunk_id = ?",
                Integer.class,
                chunkId
        );
        return count != null && count > 0;
    }

    private Optional<String> findDocumentContentHash(String docId) {
        List<String> hashes = ragJdbcTemplate.query(
                "select content_hash from rag_document where doc_id = ? limit 1",
                (rs, rowNum) -> rs.getString("content_hash"),
                docId
        );
        return hashes.stream().findFirst();
    }

    private void purgeDocumentChunks(String docId) {
        List<String> embeddingIds = ragJdbcTemplate.query(
                "select embedding_id from rag_chunk where doc_id = ? and embedding_id is not null",
                (rs, rowNum) -> rs.getString("embedding_id"),
                docId
        );
        if (!embeddingIds.isEmpty()) {
            embeddingStore.removeAll(embeddingIds);
        }
        int deletedRows = ragJdbcTemplate.update("delete from rag_chunk where doc_id = ?", docId);
        log.info("RAG - 文档 [{}] 切分策略或内容变化，已清理旧片段 {} 条、旧向量 {} 条", docId, deletedRows, embeddingIds.size());
    }

    private Metadata enrichMetadata(Metadata metadata,
                                    ChunkCandidate chunk,
                                    String docId,
                                    String chunkId,
                                    String fileName,
                                    int chunkIndex,
                                    int charCount,
                                    int tokenEstimate) {
        metadata.put("doc_id", docId)
                .put("chunk_id", chunkId)
                .put("file_name", fileName)
                .put("chunk_index", chunkIndex)
                .put("chunk_type", chunk.chunkType())
                .put("char_count", charCount)
                .put("token_estimate", tokenEstimate);
        if (chunk.sectionTitle() != null) {
            metadata.put("section_title", chunk.sectionTitle());
        }
        if (chunk.headingPath() != null) {
            metadata.put("heading_path", chunk.headingPath());
        }
        return metadata;
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "InfiniteChat.md";
        }
        return fileName.endsWith(".md") ? fileName : fileName + ".md";
    }

    private String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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

    private record MarkdownSection(String sectionTitle, String headingPath, String content) {
    }

    private record ChunkCandidate(String text, String sectionTitle, String headingPath, String chunkType) {
    }
}
