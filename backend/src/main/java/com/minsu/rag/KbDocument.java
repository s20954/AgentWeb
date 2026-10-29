package com.minsu.rag;

/**
 * RAG 知识库文档（纯内存 demo 用）。
 *
 * <p>category 可选值：
 * <ul>
 *   <li>片区介绍 —— 莫干山 5 片区的定位与气质</li>
 *   <li>政策FAQ —— 宠物/退房/发票/停车等入住政策</li>
 *   <li>房源特色 —— 设计/原木/亲子等房型风格</li>
 *   <li>游玩攻略 —— 徒步/餐饮/亲子等周边玩法</li>
 * </ul>
 */
public record KbDocument(String id, String category, String content) {
}
