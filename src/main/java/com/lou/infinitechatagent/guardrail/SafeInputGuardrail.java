package com.lou.infinitechatagent.guardrail;


import com.lou.infinitechatagent.guardrail.dto.InputSafetyResult;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

/**
 * @ClassName SafeInputGuardrail
 * @Description
 * @Author Lou
 * @Date 2026/4/12 10:51
 */

public class SafeInputGuardrail implements InputGuardrail {

    private final InputSafetyService inputSafetyService = new InputSafetyService();

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String inputText = userMessage == null ? "" : userMessage.singleText();
        InputSafetyResult result = inputSafetyService.validate(inputText);
        if (!Boolean.TRUE.equals(result.getSafe())) {
            return fatal(result.getReason());
        }
        return success();
    }
}
