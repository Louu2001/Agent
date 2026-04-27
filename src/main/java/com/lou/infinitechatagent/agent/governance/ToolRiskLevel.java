package com.lou.infinitechatagent.agent.governance;

public enum ToolRiskLevel {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int level;

    ToolRiskLevel(int level) {
        this.level = level;
    }

    public boolean gte(ToolRiskLevel other) {
        return this.level >= other.level;
    }

    public static ToolRiskLevel from(String value) {
        if (value == null || value.isBlank()) {
            return LOW;
        }
        try {
            return ToolRiskLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return LOW;
        }
    }
}
