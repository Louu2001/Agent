package com.lou.infinitechatagent.memory;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryServiceTest {

    @Test
    void similarityShouldDetectOverlappingMemory() throws Exception {
        LongTermMemoryService service = new LongTermMemoryService();

        double score = invokeSimilarity(
                service,
                "用户技术栈是 Java Spring Boot Redis MySQL",
                "用户技术栈是 Java Spring Boot Redis MySQL PgVector"
        );

        assertThat(score).isGreaterThanOrEqualTo(0.72);
    }

    @Test
    void mergeTextShouldAppendComplementaryMemory() throws Exception {
        LongTermMemoryService service = new LongTermMemoryService();

        String merged = invokeMergeText(
                service,
                "用户技术栈是 Java Spring Boot Redis",
                "用户还使用 MySQL 和 PgVector"
        );

        assertThat(merged)
                .contains("用户技术栈是 Java Spring Boot Redis")
                .contains("补充：用户还使用 MySQL 和 PgVector");
    }

    @Test
    void mergeTextShouldKeepLongerContentWhenIncomingContainsExisting() throws Exception {
        LongTermMemoryService service = new LongTermMemoryService();

        String merged = invokeMergeText(
                service,
                "用户使用 Java",
                "用户使用 Java 和 Spring Boot 开发 Agent 项目"
        );

        assertThat(merged).isEqualTo("用户使用 Java 和 Spring Boot 开发 Agent 项目");
    }

    private double invokeSimilarity(LongTermMemoryService service, String left, String right) throws Exception {
        Method method = LongTermMemoryService.class.getDeclaredMethod("similarity", String.class, String.class);
        method.setAccessible(true);
        return (double) method.invoke(service, left, right);
    }

    private String invokeMergeText(LongTermMemoryService service, String existing, String incoming) throws Exception {
        Method method = LongTermMemoryService.class.getDeclaredMethod("mergeText", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, existing, incoming);
    }
}
