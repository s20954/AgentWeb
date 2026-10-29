package com.minsu.tool.error;

/**
 * 工具错误三类型 —— 对齐《框架设计蓝图》第 6.4 节，是「AI 可控 4 分」的一半。
 *
 * <p>三种类型让模型学会区分「我错了」和「世界不允许」：
 * <ul>
 *   <li>{@link #PARAM_INVALID} 参数错 → 模型应修正参数后重调（retryable=true）</li>
 *   <li>{@link #BUSINESS_REJECT} 业务拒绝 → 模型不重试，换方案（retryable=false）</li>
 *   <li>{@link #STATE_ERROR} 顺序错 → 模型回退补前置步骤（retryable=false）</li>
 * </ul>
 */
public enum ToolErrorType {

    /** 参数错误：模型可修正参数后重调。 */
    PARAM_INVALID(true),

    /** 业务拒绝：客观条件不允许（如满房、超预算），模型不应重试，应换方案。 */
    BUSINESS_REJECT(false),

    /** 状态/顺序错误：前置步骤未完成（如没报价就想下单），模型应回退补前置。 */
    STATE_ERROR(false);

    private final boolean retryable;

    ToolErrorType(boolean retryable) {
        this.retryable = retryable;
    }

    /** 是否可重试。只有 {@link #PARAM_INVALID} 为 true。 */
    public boolean isRetryable() {
        return retryable;
    }
}
