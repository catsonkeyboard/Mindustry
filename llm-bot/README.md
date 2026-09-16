# LLM Bot 插件 (M1)

LLM 大语言模型驱动的 Mindustry AI 玩家插件。M1 目标:打通「游戏状态感知 → LLM 决策 → 游戏内执行」的端到端管线。

## 架构 (M1)

```
AgentLoop (独立守护线程, 每 intervalSec 秒或波次事件触发)
   │  ① Core.app.post → 主线程序列化战况 (StateSerializer)
   │  ② 本线程调用 LLM (LlmClient, OpenAI 兼容 API)
   │  ③ 解析 JSON 动作 (LlmClient.extractJson)
   │  ④ Core.app.post → 主线程校验并执行 (ActionExecutor)
   └  ⑤ 执行结果写入 lastResults → 下一轮 prompt 反馈闭环
```

| 模块 | 职责 |
| --- | --- |
| `bot/LlmBotPlugin` | 插件入口:事件接线、队伍解析、服务器命令 `llm` |
| `bot/BotConfig` | 配置加载(环境变量 > `config/mods/llm-bot/config.json` > 默认值) |
| `bot/llm/LlmClient` | OpenAI 兼容 HTTP 客户端(JDK HttpClient + arc Jval,零外部依赖) |
| `bot/llm/PromptBuilder` | System/User prompt 组装(含目标、动作 schema、方块目录) |
| `bot/perception/StateSerializer` | 战况 JSON 摘要 + 核心矿区扫描 + 方块目录 |
| `bot/agent/AgentLoop` | 决策循环:双触发(周期+波次)、跨线程调度、反馈记忆 |
| `bot/actions/ActionExecutor` | 动作三重校验(合法性/资源/边界) + 执行(build/chat/idle) |

## 构建与部署

```bash
# 构建 + 部署插件 jar 到开发服务器的 mods 目录
JAVA_HOME=<jdk17> ./gradlew :llm-bot:deployServer

# 启动无头服务器 (插件自动从 core/assets/config/mods/ 加载)
JAVA_HOME=<jdk17> ./gradlew server:run

# 服务器控制台输入 host 开局后,LLM bot 自动接管默认队伍
# 查看状态: llm
```

> 注意:本机需要 JDK 17(`settings.gradle` 强制)。若全局 `~/.gradle/gradle.properties` 配了代理而代理未运行,追加 `-Dhttp.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyHost= -Dhttps.proxyPort=` 绕过。

## 配置

优先级:环境变量 > 配置文件 > 默认值。首次启动会自动生成 `config/mods/llm-bot/config.json`。

```json
{
  "enabled": true,
  "baseUrl": "https://api.deepseek.com/v1",
  "apiKey": "sk-...",
  "model": "deepseek-chat",
  "team": "default",
  "intervalSec": 20,
  "maxActions": 5,
  "temperature": 0.4
}
```

环境变量:`LLM_BOT_ENABLED` `LLM_BOT_BASE_URL` `LLM_BOT_API_KEY` `LLM_BOT_MODEL` `LLM_BOT_TEAM`。

任何 OpenAI 兼容服务均可(DeepSeek / OpenAI / 通义 / Kimi / OpenRouter / Ollama `http://localhost:11434/v1`)。

## M1 动作集

| 动作 | 格式 | 说明 |
| --- | --- | --- |
| build | `{"type":"build","block":"mechanical-drill","x":..,"y":..,"rotation":0}` | 校验合法性+资源扣除后即时落块(M2 将改为建造队列) |
| chat | `{"type":"chat","message":"..."}` | 全服广播 |
| idle | `{"type":"idle"}` | 本轮不行动 |

## 已知限制 (M1)

- 建造为即时落块,不走 BaseBuilderAI 建造队列(计划 M2)
- 无科技解锁校验(方块目录为白名单粗筛)
- 单局单人验证;多人模式理论可用但未测

## 路线图

- **M2 能活下去**:建造队列接入、全动作空间(生产/指挥/研究)、科技校验
- **M3 会打仗**:长期记忆、蓝图系统、部队指挥、攻击/PvP 模式
- **M4 打得好**:token 优化、本地模型、难度分级
