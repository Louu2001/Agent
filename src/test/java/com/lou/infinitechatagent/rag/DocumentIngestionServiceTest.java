package com.lou.infinitechatagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIngestionServiceTest {

    @Test
    void splitMarkdownShouldKeepHeadingMetadata() throws Exception {
        DocumentIngestionService service = new DocumentIngestionService();
        ReflectionTestUtils.setField(service, "segmentSize", 500);
        ReflectionTestUtils.setField(service, "segmentOverlap", 80);
        ReflectionTestUtils.setField(service, "minChunkChars", 20);

        List<?> chunks = invokeSplitMarkdown(service, """
                # RAG 能力

                ## 动态知识植入

                动态知识植入会在运行时把新知识写入知识库，并同步更新向量库。

                ## 引用溯源

                引用溯源会返回文件名、章节路径和段落编号。
                """);

        assertThat(chunks).hasSize(2);
        assertThat(readRecordValue(chunks.get(0), "sectionTitle")).isEqualTo("动态知识植入");
        assertThat(readRecordValue(chunks.get(0), "headingPath")).isEqualTo("RAG 能力 > 动态知识植入");
        assertThat(readRecordValue(chunks.get(0), "chunkType")).isEqualTo("markdown_section");
        assertThat((String) readRecordValue(chunks.get(0), "text")).contains("动态知识植入");
    }

    @Test
    void splitMarkdownShouldSkipPureHeadingChunks() throws Exception {
        DocumentIngestionService service = new DocumentIngestionService();
        ReflectionTestUtils.setField(service, "segmentSize", 500);
        ReflectionTestUtils.setField(service, "segmentOverlap", 80);
        ReflectionTestUtils.setField(service, "minChunkChars", 40);

        List<?> chunks = invokeSplitMarkdown(service, """
                # 只有标题

                ## 短
                """);

        assertThat(chunks).isEmpty();
    }

    @Test
    void splitBlocksShouldKeepPageMetadata() throws Exception {
        DocumentIngestionService service = new DocumentIngestionService();
        ReflectionTestUtils.setField(service, "segmentSize", 500);
        ReflectionTestUtils.setField(service, "segmentOverlap", 80);
        ReflectionTestUtils.setField(service, "minChunkChars", 20);

        List<?> chunks = invokeSplitBlocks(service, List.of(
                new DocumentBlock("PDF 第三页包含 Redis 持久化、AOF 重写和 RDB 快照的知识说明。", null, null, "pdf_page", 3)
        ));

        assertThat(chunks).hasSize(1);
        assertThat(readRecordValue(chunks.get(0), "chunkType")).isEqualTo("pdf_page");
        assertThat(readRecordValue(chunks.get(0), "pageNumber")).isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private List<?> invokeSplitMarkdown(DocumentIngestionService service, String markdown) throws Exception {
        Method method = DocumentIngestionService.class.getDeclaredMethod("splitMarkdown", String.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(service, markdown);
    }

    @SuppressWarnings("unchecked")
    private List<?> invokeSplitBlocks(DocumentIngestionService service, List<DocumentBlock> blocks) throws Exception {
        Method method = DocumentIngestionService.class.getDeclaredMethod("splitBlocks", List.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(service, blocks);
    }

    private Object readRecordValue(Object record, String methodName) throws Exception {
        Method method = record.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(record);
    }
}
