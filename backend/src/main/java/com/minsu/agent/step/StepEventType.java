package com.minsu.agent.step;

/**
 * Agent 七步收敛 —— 每步映射为前端可见的思考链事件（对齐《框架设计蓝图》第 5.1 节）。
 *
 * <p>这是「思考链可视化」的数据源头：Agent 每走一步，向前端推一个 StepEvent。
 */
public enum StepEventType {

    /** 1 实体抽取：正在理解用户需求。 */
    EXTRACTING("extracting", "正在理解你的需求…"),

    /** 2 缺口识别：发现还缺的关键信息。 */
    GAP_FOUND("gap_found", "还缺关键信息"),

    /** 3 带着方案问：抛出 ≤3 问 + 兜底方向。 */
    ASKING("asking", "带着方案向你确认"),

    /** 4 调用地域知识：加载目的地片区知识。 */
    LOADING_KNOWLEDGE("loading_knowledge", "加载目的地片区知识"),

    /** 5 四轴筛选：从 N 套筛到候选。 */
    FILTERING("filtering", "正在筛选房源"),

    /** 6 包装方案：组织推荐方案。 */
    PACKAGING("packaging", "组织推荐方案"),

    /** 7 逼出决策：收口主推 + 切换 + 下一步。 */
    DECIDING("deciding", "给出最终建议");

    private final String code;
    private final String defaultMessage;

    StepEventType(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
