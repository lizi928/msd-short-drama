# AI 伴看

AI 伴看让用户在看当前集时，只基于当前集摘要和当前集之前的前情回答问题，并支持多轮对话记忆压缩。

## 1. 调用链

```text
AgentScope companion-agent
  ├─ get_episode_summary(episodeId)
  │    → GET 8107 /internal/ai/companion/tools/episodes/{id}/summary
  └─ search_drama_context(query, currentEpisodeId)
       → POST 8107 /internal/ai/companion/tools/context

Vue / 客户端
  → 网关 /api/content/internal/ai/companion/chat
  → content-service CompanionChatService
     ├─ 当前集摘要
     ├─ 当前集之前的前情
     ├─ DeepSeek 生成回复
     └─ SummarizingChatMemory（最近 20 条 + Redis 历史摘要）
```

依赖方向：`agent-service → content-service → drama`。drama 不反调 content。

## 2. 内部接口

### 2.1 当前集摘要

```http
GET /internal/ai/companion/tools/episodes/{episodeId}/summary
```

```json
{
  "episodeId": 6005,
  "dramaId": 5001,
  "episodeNo": 5,
  "episodeTitle": "第5集 告白",
  "dramaTitle": "隐婚总裁的甜妻",
  "summary": "顾沉舟在雨夜坦白心意……",
  "source": "seed",
  "degraded": false,
  "degradedFlags": []
}
```

- `source`：`seed` 已有摘要，`generated` 本次生成，`missing` 缺失或失败，`generating` 其他实例生成中。
- 剧集不可见时 `data=null`。
- 摘要缺失时走 DB claim，只有认领成功的实例调用轻量模型生成并回写。

### 2.2 前情检索

```http
POST /internal/ai/companion/tools/context
```

```json
{"query":"项目被截胡","currentEpisodeId":6005,"topK":3}
```

```json
{
  "currentEpisodeId": 6005,
  "dramaId": 5001,
  "currentEpisodeNo": 5,
  "items": [
    {
      "episodeId": 6003,
      "episodeNo": 3,
      "episodeTitle": "第3集 危机",
      "summary": "苏晚负责的项目被对手截胡……",
      "score": 0.0328,
      "retrievalSources": ["vector", "bm25"]
    }
  ],
  "degraded": false,
  "degradedFlags": [],
  "elapsedMs": 1187
}
```

- 只返回 `docType=episode`、同一 `dramaId`、`episodeNo < currentEpisodeNo` 的文档。
- 检索后再校验 `episodeId < currentEpisodeId`，防止索引数据异常导致剧透。
- 第 1 集直接返回空列表，不调用检索。
- ES 中没有该剧分集文档时标记 `episode_index`，调用方不得编造前情。

### 2.3 伴看聊天

```http
POST /api/content/internal/ai/companion/chat
Authorization: Bearer <token>
```

```json
{"message":"这一集他们为什么突然和好了？","sessionId":"sec8-demo","episodeId":6005}
```

```json
{
  "reply": "……",
  "sessionId": "sec8-demo",
  "episodeId": 6005,
  "degraded": false,
  "degradedFlags": [],
  "elapsedMs": 2496
}
```

- `message` 1-1000 字，`sessionId` 只允许字母、数字、下划线和短横线。
- 网关注入 `X-User-Id`；直连缺少该头时返回 400。
- 会话 ID：`u{userId}:d{dramaId}:s{sessionId}`，换集仍保持连续。
- 整体超时 30 秒，超时返回固定兜底文案。

### 2.4 前端伴看面板

- Feed 右侧操作栏增加“伴看”按钮；当前集免费或已解锁时才打开，未解锁只提示。
- [CompanionPanel](../frontend/src/components/companion/CompanionPanel.vue) 负责消息列表、快捷问题、发送状态和降级提示。
- 每部剧生成一个 `sessionId`，消息和会话 ID 存在 `sessionStorage` 的 `msd.companion.{dramaId}`，最多 50 条；离开再进入可继续本次对话。
- 打开面板时暂停播放并上报进度，与评论/选集弹层一致。
- “新会话”只清理前端记录，服务端旧会话按 TTL 自然过期。

### 2.5 摘要批量预热

```http
POST /internal/ai/companion/summaries/warmup?dramaId=5002&limit=1
```

```json
{"code":0,"message":"成功","data":{"scanned":1,"generated":1,"skipped":0,"failed":0,"degradedFlags":[]}}
```

- 只允许直连 8107，网关不路由。
- drama 先返回该剧缺失摘要且可重试的在线剧集 ID，content-service 逐集复用 claim、生成、回写和单集索引。
- `limit` 上限 20；单集失败不中断整批，`degradedFlags` 汇总降级标记。

## 3. AgentScope 工具

Supervisor 根据 `AGENTS.md` 把伴看问题路由到 `companion-agent`，子 Agent 工具由 [CompanionTools](../msd-agent-service/src/main/java/com/msd/agent/tool/CompanionTools.java) 注册：

| 工具 | 入参 | 返回 |
|---|---|---|
| `get_episode_summary` | `episodeId` | `[EPISODE]`、`[SUMMARY]`、`[SOURCE]`、`[NOTICE]` |
| `search_drama_context` | `query`、`currentEpisodeId` | `[CONTEXT]`、`[1..N]`、`[NOTICE]` |

- 失败统一返回 `[NOT_FOUND]`、`[SUMMARY_MISSING]`、`[CONTEXT_EMPTY]`、`[DEGRADED]`，不抛异常。
- `POST /api/agent/internal/ai/agent/chat` 可选传 `episodeId`；服务端会在用户消息前加 `[当前剧集ID=...]`，供 companion-agent 直接使用。
- `companion-agent.md` 要求先查当前集摘要，再按需检索前情，严禁提及后续剧情。
- 实测 Supervisor 路由到 `companion-agent`，并行调用两个工具；回复只引用第 1-4 集，未出现第 6 集或大结局。

## 4. 防剧透

1. **索引过滤**：Milvus/ES 都限制 `docType=episode`、`dramaId=当前剧`、`episodeNo<当前集`。
2. **后置校验**：再次检查 metadata 的剧集、集数和 `episodeId<currentEpisodeId`。
3. **当前集摘要隔离**：当前集摘要只由摘要接口返回，前情检索永远不返回当前集及以后。
4. **Prompt 约束**：系统提示明确禁止提及、猜测、暗示后续剧情；用户追问统一回复「为了不剧透，我只陪你看到当前这一集。」

## 5. 会话记忆

### 5.1 Redis 结构

```text
key:   msd:companion:memory:u1003:d5001:ssec8-demo
value: {"summary":"历史摘要","messages":[{"type":"user","text":"..."}, ...]}
ttl:   7 天
```

- 最近 `max-messages=20` 条消息保留原文。
- 溢出消息先留在归档中，累计 `compression-batch=4` 条后由轻量模型合并成历史摘要。
- 历史摘要作为 `SystemMessage` 放在窗口消息之前。
- Redis 不可用时 fail-open：只使用单实例内存窗口，聊天继续可用，响应标记 `memory_summary`。
- 跨实例压缩使用 Redisson `RLock`；抢不到锁时本次跳过压缩，正常追加。

### 5.2 为什么按批压缩

如果每溢出一条消息就调用一次模型，模型只拿到一条消息，容易补写未出现的人物和情节。按 4 条一批压缩后，摘要能覆盖完整问答，实测不再产生「林晚」「咖啡馆」这类原文外内容。

## 6. 配置

Nacos `content-config.yaml`：

```yaml
msd:
  ai:
    companion:
      enabled: true
      summary:
        max-chars: 120
      context:
        default-top-k: 3
        max-top-k: 5
        max-summary-chars: 200
      memory:
        max-messages: 20
        compression-batch: 4
        max-archived-messages: 200
        ttl-days: 7
        max-summary-chars: 240
        compress-message-chars: 300
        lock-wait-ms: 200
        lock-lease-ms: 10000
      chat:
        timeout-ms: 30000
      light-model:
        model: ${COMPANION_LIGHT_MODEL:qwen-flash}
        temperature: 0.1
        max-tokens: 256
        timeout-ms: 4000
        base-url: ""
        api-key: ""
```

- `base-url`/`api-key` 为空时复用 `spring.ai.openai.base-url` 和 `spring.ai.openai.api-key`。
- 当前账号上 `qwen-turbo` 返回 429，默认使用 `qwen-flash`，可通过 `COMPANION_LIGHT_MODEL` 覆盖。

## 7. 降级标记

| 标记 | 含义 |
|---|---|
| `drama` | 短剧服务不可用 |
| `summary` | 当前集摘要缺失或生成失败 |
| `context` | 前情检索降级 |
| `vector` | Milvus 向量检索降级 |
| `bm25` | Elasticsearch 检索降级 |
| `episode_index` | 分集索引为空，需要 reindex |
| `generation` | 伴看回复生成失败，使用兜底文案 |
| `memory_summary` | 记忆压缩或 Redis 归档失败 |
| `not_found` | 剧集不存在或已下线 |
| `timeout` | 聊天整体超时 |

## 8. 手工验证

```bash
# 1. 当前集摘要
curl http://127.0.0.1:8107/internal/ai/companion/tools/episodes/6005/summary

# 2. 前情检索：只允许第 1-4 集
curl -X POST http://127.0.0.1:8107/internal/ai/companion/tools/context \
  -H 'Content-Type: application/json' \
  -d '{"query":"项目被截胡","currentEpisodeId":6005,"topK":3}'

# 3. 伴看聊天（经网关）
TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/companion/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"顾沉舟为什么突然告白？","sessionId":"sec8-demo","episodeId":6005}'

# 4. 连续 12 轮后检查 Redis 记忆
redis-cli -h 192.168.33.129 -a "$REDIS_PASSWORD" get \
  'msd:companion:memory:u1003:d5001:ssec8-demo'
```

实测（2026-10-25）：

- 12 轮聊天后 Redis 保留 20 条消息 + 143 字历史摘要，TTL 7 天。
- 摘要包含顾沉舟、苏晚、第 3/4 集事实，没有原文外人物。
- 全部回复未出现「第 6 集」「大结局」等未来剧情。
- 前情检索同时命中 `vector` 和 `bm25` 时仍只返回第 1-4 集。
- 停掉 ES：`degradedFlags=["bm25"]`，仅返回向量结果；恢复后标记消失。
- 停掉 Milvus：`degradedFlags=["vector"]`，仅返回 BM25 结果；恢复后标记消失。
- 同时停掉 ES 和 Milvus：items 为空，`degradedFlags=["vector","bm25"]`；Agent 明确回复「查不到前情」，未编造剧情。
- Redis 指向不可用端口：聊天仍返回正常回复，`degradedFlags=["memory_summary"]`，耗时约 4.5 秒。
- 第 1 集前情为空且无降级标记；不存在的剧集，摘要和上下文均返回 `data=null`。
- 非法 `sessionId`、空 `message`、`episodeId=0` 均返回 400；网关访问 tools 路径返回 404。
- 摘要预热 `dramaId=5002&limit=1` 返回 `generated=1`，再次查询候选为空，6104 摘要已落库。
- 前端伴看面板已通过 `npm run build`，网关静态资源包含“AI 伴看”入口。
