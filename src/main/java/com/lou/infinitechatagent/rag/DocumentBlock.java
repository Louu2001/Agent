package com.lou.infinitechatagent.rag;

record DocumentBlock(
        String text,
        String sectionTitle,
        String headingPath,
        String blockType,
        Integer pageNumber
) {
}
