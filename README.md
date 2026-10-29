# 民宿预约 Agent

> 替用户做减法的民宿预约顾问 —— 用户带来一句话，带走一个决断。不是搜索引擎，是决策助手。

参加「全国高校 AI Web 开发挑战赛（传智杯）」的 AI Native Web 应用。前端 Vue3 主角，后端 Java Spring Boot 轻接入层（仅做 AI 接入）。

---

## 一、项目结构

```
AgentWeb/
├── frontend/                 # 前端：Vue3 + TS + Tailwind + Pinia
│   ├── src/
│   │   ├── main.ts           # 入口，挂载 Pinia
│   │   ├── App.vue           # 根组件
│   │   ├── views/ChatView.vue       # 主对话页
│   │   ├── components/              # ChatInput / ChatMessage / ThinkingChain /
│   │   │                            #   VoiceInput（语音）/ ImageSelect（看图选房）
│   │   ├── api/                     # chat.ts / search.ts（SSE 流式 + REST）
│   │   ├── stores/                  # app.ts / chat.ts（Pinia 状态）
│   │   └── types/chat.ts            # 前端类型定义
│   ├── index.html
│   ├── vite.config.ts        # 含 /api → 8080 的 dev 代理
│   └── package.json
├── backend/                  # 后端：Spring Boot 3 + WebFlux（轻接入层）
│   ├── src/main/java/com/minsu/
│   │   ├── MinsuApplication.java
│   │   ├── agent/            # Agent 层：七步收敛 + 缺口识别 + 思考链事件
│   │   │   └── llm/          # LLM 客户端抽象 + DeepSeek 实现
│   │   ├── tool/             # 工具层：6 工具 + 六槽位 + 错误三类型
│   │   │   ├── executor/     #   搜索/报价/下单/查单/取消/问房 + 房/单内存存储
│   │   │   └── error/        #   错误类型与异常
│   │   ├── rag/              # 向量检索（SQLite 存储 + 地域知识）
│   │   ├── config/           # 跨域等配置
│   │   └── web/              # ChatController（SSE 流式）/ Tool / Search / Vision / Health
│   ├── src/main/resources/application.yml   # 密钥一律走环境变量
│   └── src/test/java/com/minsu/            # 单测（含工具层混淆矩阵）
├── deploy/                   # 部署配置（Nginx / systemd / 一键脚本 / 环境变量模板）
│   ├── nginx-minsu.conf
│   ├── minsu-backend.service
│   ├── deploy.sh
│   └── .env.example
├── tools/                    # 本地开发工具（nginx 配置）
├── bug-report-20261027.md    # 工具层实测 bug 报告
└── 作品介绍文档.md           # 参赛作品说明
```

## 二、四层架构

```
前端层   Vue3 + TS + Tailwind + Pinia    （流式渲染 · 思考链可视化 · 语音 · 看图选房）
Agent层  七步收敛 + 缺口识别 + 地域知识     （映射为前端可见的思考链）
工具层   6 工具 + 六槽位 + 边界句 + 错误三类型（Function Calling）
接入层   Java Spring Boot（LLM 流式代理 / Tool Calling / RAG / 视觉 / 房源 CRUD）
```

## 三、核心能力

- **七步收敛思考链**：Agent 的推理过程实时映射为前端可见的思考步骤，命中「可解释性」评分点。
- **6 个业务工具**：`search_rooms` / `quote_price` / `create_order` / `get_order` / `cancel_order` / `ask_house_info`，统一六槽位协议 + 错误三类型（可重试 / 业务拒绝 / 参数非法）。
- **多模态**：语音输入（Web Speech API）+ 看图选房（视觉模型）。
- **RAG 地域知识**：每个目的地的地域知识单独构建，抑制大模型幻觉。
- **SSE 流式对话**：后端流式代理，前端逐 token 渲染。

## 四、快速开始

### 环境要求

- Node ≥ 20（已测 22）
- Java 21
- Maven 3.9+

### 1. 启动后端

```bash
cd backend
mvn spring-boot:run
```

健康检查：`curl http://127.0.0.1:8080/api/health`，应返回 `{"status":"ok",...}`。

### 2. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。

### 3. 跑测试

```bash
cd backend && mvn test          # 后端单测（含工具层混淆矩阵）
cd frontend && npm run build    # 前端类型检查 + 生产构建
```

## 五、环境变量

密钥一律走环境变量，绝不入公开仓库。模板见 `deploy/.env.example`。

| 变量 | 说明 |
| --- | --- |
| `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` | 主模型（DeepSeek） |
| `VISION_API_KEY` / `VISION_BASE_URL` / `VISION_MODEL` | 视觉模型（看图选房） |
| `SERVER_PORT` | 后端端口，默认 8080 |
| `VECTOR_DB_PATH` | SQLite 向量库路径 |

复制模板并填写真实密钥：

```bash
cp deploy/.env.example deploy/.env
```

---

*赛事评分是北极星，每个 PR 自问「这对应哪个得分点」。*
