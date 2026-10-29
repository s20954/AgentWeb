package com.minsu.tool.executor;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import com.minsu.tool.error.ToolErrorType;
import com.minsu.tool.error.ToolException;

/**
 * 执行器基类：参数解析辅助 + 错误三类型的统一抛出。
 *
 * <p>参数缺失/非法 → {@link ToolErrorType#PARAM_INVALID}（可重试）。
 */
public abstract class AbstractToolExecutor implements ToolExecutor {

    protected String reqStr(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "缺少必填参数 " + key + "，请补全后重试（retryable）。");
        }
        return v.toString().trim();
    }

    protected Integer optInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "参数 " + key + " 不是合法整数：" + v + "，请修正后重试（retryable）。");
        }
    }

    protected LocalDate reqDate(Map<String, Object> args, String key) {
        String s = reqStr(args, key);
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ToolException(ToolErrorType.PARAM_INVALID,
                    "参数 " + key + " 不是合法日期（应为 YYYY-MM-DD）：" + s + "，请修正后重试（retryable）。");
        }
    }
}
