package com.lou.infinitechatagent.tool;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import dev.langchain4j.agent.tool.Tool;

/**
 * @ClassName TimeTool
 * @Description
 * @Author Lou
 * @Date 2026/4/12 10:15
 */

public class TimeTool {

    @Tool("getCurrentTime")
    public String getCurrentTimeInShanghai() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE (中国标准时间)"));
    }
}

