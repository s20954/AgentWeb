# 栖迟 Agent · 民宿预约助手

> 替用户做减法的民宿预约顾问 —— 用户带来一句话，带走一个决断。不是搜索引擎，是决策助手。

「栖迟 Agent」是一套 **多目的地民宿预约 Web 应用**，由「用户端门户网站」+「AI 对话预约」两部分组成。前端 Vue3 主导，后端 Java Spring Boot 轻接入层（仅做 AI 接入与流式代理）。

品牌 Slogan：**一语既出，佳宿自现**（对话端）/ **言至宿定**（门户端）。

---

## 一、项目结构

```
AgentWeb/
├── frontend/                       # 前端：Vue3 + TS + Tailwind + Pinia + vue-router
│   ├── src/
│   │   ├── main.ts                 # 入口，挂载 Pinia + Router
│   │   ├── App.vue                 # 根组件
│   │   ├── router/index.ts         # 门户路由（/home /homestay /spot /order /profile + /agent 对话页）
│   │   ├── views/
│   │   │   ├── ChatView.vue        # AI 对话预约页（思考链 + SSE 流式）
│   │   │   └── portal/             # 门户页面：PortalLayout / Home / Homestay(列表+详情)
│   │   │                           #   / Spot(列表+详情) / Order / Profile / NotFound
│   │   ├── components/             # ChatInput / ChatMessage / ThinkingChain / RoomCards /
│   │   │                           #   VoiceInput（语音）/ ImageSelect（看图选房）
│   │   ├── mock/data.ts            # 门户 Mock 数据层（民宿/景点/订单/横幅，覆盖全国多地）
│   │   ├── api/                    # chat.ts / search.ts（SSE 流式 + REST）
│   │   ├── stores/                 # app.ts / chat.ts（Pinia 状态）
│   │   └── types/chat.ts           # 前端类型定义
│   ├── index.html
│   ├── vite.config.ts              # 含 /api → 8080 的 dev 代理
│   └── package.json
├── backend/                        # 后端：Spring Boot 3 + WebFlux（轻接入层）
│   ├── src/main/java/com/minsu/
│   │   ├── MinsuApplication.java
│   │   ├── agent/                  # Agent 层：七步收敛 + 缺口识别 + 思考链事件
│   │   │   ├── ConversationMemory.java  # 会话级对话记忆（跨多轮带上下文）
│   │   │   ├── step/               # 思考链 StepEvent / StepEventType
│   │   │   └── llm/                # LLM 客户端抽象 + DeepSeek 实现
│   │   ├── tool/                   # 工具层：6 工具 + 六槽位 + 错误三类型
│   │   │   ├── executor/           #   搜索/报价/下单/查单/取消/问房 + 房/单内存存储
│   │   │   └── error/              #   错误类型与异常
│   │   ├── rag/                    # 向量检索（SQLite 存储 + 地域知识）
│   │   ├── config/                 # 跨域等配置
│   │   └── web/                    # Chat / Search / Vision / Tool / Health 控制器
│   │       ├── SearchController.java  # 智能检索：本地知识库 → 联网 → LLM 规划
│   │       └── VisionController.java  # 看图选房（图片风格/预算倾向推断）
│   ├── src/main/resources/application.yml   # 密钥一律走环境变量
│   └── src/test/java/com/minsu/            # 单测（含工具层混淆矩阵）
├── deploy/                         # 部署配置（Nginx / systemd / 一键脚本 / 环境变量模板）
│   ├── nginx-minsu.conf
│   ├── minsu-backend.service
│   ├── deploy.sh
│   └── .env.example
├── tools/                          # 本地开发工具（nginx 配置）
└── bug-report-20261027.md          # 工具层实测 bug 报告
```

## 二、四层架构

```
前端层   Vue3 + TS + Tailwind + Pinia + vue-router（门户多页 · 流式渲染 · 思考链可视化 · 语音 · 看图选房）
Agent层  七步收敛 + 缺口识别 + 会话记忆 + 地域知识（映射为前端可见的思考链）
工具层   6 工具 + 六槽位 + 边界句 + 错误三类型（Function Calling）
接入层   Java Spring Boot（LLM 流式代理 / Tool Calling / RAG / 视觉 / 联网规划 / 房源 CRUD）
```

## 三、核心能力

- **完整门户网站**：首页 / 民宿列表与详情 / 景点列表与详情 / 我的订单 / 个人中心 + 404 页，vue-router 多页路由，响应式导航（含移动端抽屉）。
- **多目的地支持**：Mock 数据覆盖莫干山 / 大理 / 北京 / 成都 / 三亚 / 厦门等全国多地，非单一景区。
- **AI 对话预约（核心）**：
  - **七步收敛思考链**：Agent 推理过程实时映射为前端可见思考步骤，命中「可解释性」评分点。
  - **缺口识别 + 追问**：信息不全先反问（目的地/人数/天数），确认后再规划，不瞎猜默认值。
  - **联网搜索 + LLM 自主规划**：搜前 `buildSearchQuery` 清洗口语 → 必应联网 → `planWithLlm` 走七步收敛输出结构化行程方案，而非甩百科链接。
  - **防污染沉淀**：只有 LLM 规划成功才沉淀回知识库，降级拼接与无关结果不写入。
- **6 个业务工具**：`search_rooms` / `quote_price` / `create_order` / `get_order` / `cancel_order` / `ask_house_info`，统一六槽位协议 + 错误三类型（可重试 / 业务拒绝 / 参数非法）。
- **多模态**：语音输入（Web Speech API）+ 看图选房（视觉模型，图片压缩后推断风格与预算倾向）。
- **会话记忆**：跨多轮带上下文，用户说「那里/它/刚才说的」能从历史还原所指。
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

- 门户网站：`/`（默认跳 `/home`）
- AI 对话预约：`/agent`

### 3. 跑测试

```bash
cd backend && mvn test          # 后端单测（含工具层混淆矩阵）
cd frontend && npm run build    # 前端类型检查 + 生产构建
```

## 五、环境变量

密钥一律走环境变量，绝不入公开仓库。模板见 `deploy/.env.example`。

| 变量 | 说明 |
| --- | --- |
| `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` | 主模型（DeepSeek，含联网规划） |
| `VISION_API_KEY` / `VISION_BASE_URL` / `VISION_MODEL` | 视觉模型（看图选房） |
| `SERVER_PORT` | 后端端口，默认 8080 |
| `VECTOR_DB_PATH` | SQLite 向量库路径 |

复制模板并填写真实密钥：

```bash
cp deploy/.env.example deploy/.env
```

> 注意：`LLM_API_KEY` 需在启动后端 Java 进程的终端中生效（Windows 下修改环境变量后需新开终端），否则联网搜索会降级为拼接字符串。

--
