package com.minsu.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 会话状态 —— 承载七步收敛过程中的中间态。
 *
 * <p>一次对话收敛的实体与缺口，供各步之间传递。
 */
public class AgentState {

    // ---- 实体抽取结果（第 1 步）----
    private String destination;      // 目的地，如「莫干山」
    private String checkIn;          // 入住日期 YYYY-MM-DD
    private String checkOut;         // 离店日期 YYYY-MM-DD
    private Integer budgetPerNight;  // 每晚预算
    private String relationship;     // 出行关系：情侣/朋友/亲子/家庭
    private String style;            // 风格偏好
    private Integer guests;          // 人数

    // ---- 缺口（第 2 步）----
    private final List<String> gaps = new ArrayList<>();

    // ---- 候选房源（第 5 步）----
    private List<Map<String, Object>> candidates = new ArrayList<>();

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getCheckIn() { return checkIn; }
    public void setCheckIn(String checkIn) { this.checkIn = checkIn; }
    public String getCheckOut() { return checkOut; }
    public void setCheckOut(String checkOut) { this.checkOut = checkOut; }
    public Integer getBudgetPerNight() { return budgetPerNight; }
    public void setBudgetPerNight(Integer budgetPerNight) { this.budgetPerNight = budgetPerNight; }
    public String getRelationship() { return relationship; }
    public void setRelationship(String relationship) { this.relationship = relationship; }
    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
    public Integer getGuests() { return guests; }
    public void setGuests(Integer guests) { this.guests = guests; }

    public List<String> getGaps() { return gaps; }
    public List<Map<String, Object>> getCandidates() { return candidates; }
    public void setCandidates(List<Map<String, Object>> candidates) { this.candidates = candidates; }

    /** 是否已具备「必问三要素」（日期/预算/关系）。 */
    public boolean hasMandatoryInfo() {
        return checkIn != null && checkOut != null && budgetPerNight != null && relationship != null;
    }
}
