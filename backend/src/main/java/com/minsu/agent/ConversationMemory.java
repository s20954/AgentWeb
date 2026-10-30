package com.minsu.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.minsu.agent.llm.LlmMessage;

/**
 * 会话级对话记忆 —— 让同一个会话里的前后问答有上下文连续性。
 *
 * <p>背景：此前 ChatController（AgentService）与 SearchController 每次调 LLM 都只发
 * 「system + 当前这一句」，之前的提问、之前的回答（包括「知识库没有」这种失败回答）
 * 完全不会带进后续请求，用户感觉每句话都在和失忆的 Agent 从零开始。
 *
 * <p>本组件按 sessionId 记录最近若干轮「用户问 + Agent 答」，两路接口共用：
 * <ul>
 *   <li>调 LLM 前用 {@link #recent(String)} 取出历史，拼进 messages（system 之后、当前句之前）；</li>
 *   <li>每轮回答完毕用 {@link #appendTurn(String, String, String)} 写入。</li>
 * </ul>
 *
 * <p>边界控制：每会话最多保留 {@value #MAX_TURNS} 轮（超出从头丢弃）；
 * {@value #TTL_MS} 毫秒未活动视为新会话，旧历史清空。内存实现，重启即失——
 * 民宿场景的多轮规划都在一次会话内完成，无需持久化。
 */
@Component
public class ConversationMemory {

    /** 每会话最多保留的对话轮数（一轮 = 用户问 + Agent 答 两条消息）。 */
    private static final int MAX_TURNS = 10;
    /** 会话不活跃多久后丢弃历史（30 分钟）。 */
    private static final long TTL_MS = 30 * 60 * 1000L;
    /**
     * 会话条目上限。前端每次刷新页面都会生成新 sessionId，过期条目只有被再次访问才会清理，
     * 旧 sessionId 永远不会再被访问 → 不主动清扫会无界堆积，故设上限兜底。
     */
    private static final int MAX_SESSIONS = 1000;

    private static class SessionHistory {
        final Deque<LlmMessage> messages = new ArrayDeque<>();
        long lastTouch = System.currentTimeMillis();
    }

    private final ConcurrentMap<String, SessionHistory> sessions = new ConcurrentHashMap<>();

    /**
     * 取该会话的近期对话历史（user/assistant 交替，按时间正序）。
     * 过期则清空并按空历史处理。
     */
    public List<LlmMessage> recent(String sessionId) {
        SessionHistory h = sessions.get(sessionId);
        if (h == null) {
            return List.of();
        }
        synchronized (h) {
            if (expired(h)) {
                h.messages.clear();
                return List.of();
            }
            return new ArrayList<>(h.messages);
        }
    }

    /** 记录一轮问答：用户说了 userMessage，Agent 回答了 assistantMessage。 */
    public void appendTurn(String sessionId, String userMessage, String assistantMessage) {
        if (sessionId == null || userMessage == null || userMessage.isBlank()
                || assistantMessage == null || assistantMessage.isBlank()) {
            return;
        }
        evictIfNeeded();
        SessionHistory h = sessions.computeIfAbsent(sessionId, k -> new SessionHistory());
        synchronized (h) {
            if (expired(h)) {
                h.messages.clear();
            }
            h.messages.addLast(LlmMessage.user(userMessage));
            h.messages.addLast(LlmMessage.assistant(assistantMessage));
            while (h.messages.size() > MAX_TURNS * 2) {
                h.messages.pollFirst();
            }
            h.lastTouch = System.currentTimeMillis();
        }
    }

    /** 清空指定会话历史（前端重置会话时调用）。 */
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    /** 条目数超上限时：先清过期会话，仍超限再按最后活跃时间从最旧的开始删。 */
    private void evictIfNeeded() {
        if (sessions.size() <= MAX_SESSIONS) {
            return;
        }
        sessions.entrySet().removeIf(e -> expired(e.getValue()));
        while (sessions.size() > MAX_SESSIONS) {
            String oldest = sessions.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().lastTouch, b.getValue().lastTouch))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) {
                break;
            }
            sessions.remove(oldest);
        }
    }

    private boolean expired(SessionHistory h) {
        return System.currentTimeMillis() - h.lastTouch > TTL_MS;
    }
}
