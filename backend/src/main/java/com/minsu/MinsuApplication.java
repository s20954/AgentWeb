package com.minsu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 民宿预约 Agent —— 轻后端接入层入口。
 *
 * <p>职责边界（对齐《框架设计蓝图》第 2 章）：
 * 本后端只做四件事——① LLM API 流式代理(SSE) ② Tool Calling 执行 ③ RAG 检索 ④ 房源数据 CRUD。
 * 业务逻辑全在前端 + Agent 层，本层不堆业务。
 */
@SpringBootApplication
public class MinsuApplication {

    public static void main(String[] args) {
        SpringApplication.run(MinsuApplication.class, args);
    }
}
