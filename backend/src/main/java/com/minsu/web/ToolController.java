package com.minsu.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minsu.tool.ToolDispatcher;
import com.minsu.tool.ToolResult;

import reactor.core.publisher.Mono;

/**
 * 工具层 HTTP 接口 —— 供 Agent 层 / 前端调用。
 *
 * <p>两个端点：
 * <ul>
 *   <li>{@code GET /api/tools} 返回 6 个工具的 Function Calling Schema（喂 LLM）</li>
 *   <li>{@code POST /api/tools/{name}/run} 执行指定工具（userId 从请求头取，模型不可填）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolDispatcher dispatcher;

    public ToolController(ToolDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 返回工具 Schema 清单。 */
    @GetMapping
    public Mono<Object> listTools() {
        return Mono.just(Map.of(
                "count", dispatcher.registry().all().size(),
                "tools", dispatcher.registry().allFunctionSpecs()
        ));
    }

    /** 执行工具。userId 从请求头 {@code X-User-Id} 注入（鉴权从上下文取，不做成模型可填参数）。 */
    @PostMapping("/{name}/run")
    public Mono<ToolResult> run(
            @org.springframework.web.bind.annotation.PathVariable String name,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody(required = false) Map<String, Object> args) {
        // 空 body 也走工具标准错误协议（而非 Spring 默认裸 400）
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        return Mono.just(dispatcher.dispatch(userId, name, safeArgs));
    }
}
