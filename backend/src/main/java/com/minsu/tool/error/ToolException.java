package com.minsu.tool.error;

/**
 * 工具异常：携带错误三类型 + 四槽位错误信息。
 *
 * <p>四槽位（对齐《框架设计蓝图》第 6.4 节，80-100 字上限）：
 * 发生了什么 + 为什么 + 怎么改 + 能不能重试。
 */
public class ToolException extends RuntimeException {

    private final ToolErrorType errorType;

    public ToolException(ToolErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public ToolErrorType getErrorType() {
        return errorType;
    }

    /** 工具标准错误返回结构（喂给模型，模型据此决策下一步）。 */
    public record ErrorBody(String errorType, boolean retryable, String message) {
    }
}
