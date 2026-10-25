# RAG 内容推荐

`msd-content-service`（8107）实现短剧 RAG 推荐：查询改写、Milvus 向量检索与 Elasticsearch BM25 并行召回、RRF 融合、DashScope Rerank 精排、基于检索资料的推荐理由生成。

## 1. 架构

```text
msd-drama (MySQL)
   │ GET /internal-source/rag/dramas?page=1&size=200
   ▼
msd-content-service (8107)
   ├─ DramaCatalogClient (Feign)
   ├─ DramaDocumentBuilder → Milvus VectorStore + Elasticsearch BM25
   ├─ LlmQueryTransformer (DeepSeek)
   ├─ HybridRetriever
   │    ├─ Milvus Top 20
   │    └─ ES BM25 Top 20
   │    └─ RrfMerger(k=60) → Top 20
   ├─ DashScopeRerankPostProcessor → Top 3
   └─ RetrievalAugmentationAdvisor + ChatClient → answer + sources
   ▲
   │ POST /api/content/internal/ai/rag/recommend
msd-gateway (JWT)
```

- 不引入 `spring-ai-starter-vector-store-milvus`，避免 Milvus 未启动时应用启动失败。
- 不引入 `spring-boot-starter-web`，服务运行在 WebFlux/Netty 上。
- 与 `msd-agent-service` 的 AgentScope Supervisor/A2A 链路隔离，避免 Jackson/victools 版本冲突。

## 2. 五段链路

### 2.1 数据向量化

[DramaDocumentBuilder](../msd-content-service/src/main/java/com/msd/content/index/DramaDocumentBuilder.java) 把短剧导出数据转换为 Document：

```text
标题：隐婚总裁的甜妻
副标题：先婚后爱，甜到上头
分类：甜宠
标签：甜宠、重生
演员：林一、苏晚
简介：……（最多 1200 字）
剧集：第1集 闪婚；第2集 同居；……（最多 30 条）
```

- `Document.id = drama-{dramaId}`，Milvus 和 ES 共用同一去重键。
- 短剧 metadata 包含 `docType=drama`、`dramaId`、`title`、`subtitle`、`coverUrl`、`categoryId`、`categoryName`、`tags`、`actors`、`episodeCount`、`viewCount`、`likeCount`、`publishTime`、`snippet`。
- Embedding 使用 `qwen3.7-text-embedding-flash`，1024 维；单批最多 20 条（`msd.ai.rag.vector.batch-size`）。
- 写入前校验/固定维度，collection 为 `msd_drama_rag_v1`。

分集文档：

- `Document.id = episode-{episodeId}`，供 AI 伴看检索当前集之前的前情。
- metadata 增加 `docType=episode`、`dramaId`、`dramaTitle`、`episodeId`、`episodeNo`、`episodeTitle`、`summary`、`snippet`。
- embedding 文本为「剧名 + 第 N 集标题 + 本集摘要」。
- 推荐检索固定过滤 `docType=drama`；前情检索过滤 `docType=episode`、`dramaId`、`episodeNo < 当前集`。
- ES mapping 增加 `docType`、`episodeId`、`episodeNo`、`episodeTitle`、`dramaTitle`、`summary`、`summaryTokens`、`episodeTitleTokens`，旧索引必须全量 reindex。

### 2.2 查询改写

[LlmQueryTransformer](../msd-content-service/src/main/java/com/msd/content/rag/LlmQueryTransformer.java) 使用独立 `rewriteChatClient`（DeepSeek）把口语化需求改写成检索关键词：

```text
有没有那种先婚后爱的甜宠剧
→ 先婚后爱 甜宠
```

- 3 秒超时，失败回退原查询并标记 `rewrite`。
- 失败后 30 秒内不再调用模型。
- 保留 `Query.history` 和 `Query.context`，`RagTrace` 继续传给检索器和精排器。

### 2.3 混合检索与 RRF

[HybridRetriever](../msd-content-service/src/main/java/com/msd/content/rag/HybridRetriever.java) 并行执行两路：

- 向量：`LazyMilvusStoreProvider.search(query, 20)`，Milvus `COSINE`，不设相似度阈值。
- BM25：[ElasticsearchBm25Store](../msd-content-service/src/main/java/com/msd/content/rag/ElasticsearchBm25Store.java)，jieba 预分词 + whitespace analyzer，多字段 boost：
  - `titleTokens` 4.0
  - `tagTokens` 3.0
  - `subtitleTokens` 2.5
  - `actorTokens` 2.0
  - `descriptionTokens` 1.2
  - `episodeTokens` 1.0
  - `searchText` 0.5

两路各自限时（向量 3s、BM25 2s），单路失败/超时只标记对应降级。[RrfMerger](../msd-content-service/src/main/java/com/msd/content/rag/RrfMerger.java) 按 `Document.id` 去重并计算：

```text
score += 1 / (k + rank), k = 60
```

输出 Top 20，metadata 增加 `rrfScore`、`vectorRank`、`bm25Rank`、`vectorScore`、`bm25Score`、`retrievalSources`。

### 2.4 Rerank

[DashScopeRerankPostProcessor](../msd-content-service/src/main/java/com/msd/content/rag/DashScopeRerankPostProcessor.java) 调用：

```text
POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
Authorization: Bearer ${DASHSCOPE_API_KEY}
```

请求 Top 20，按 `output.results[].relevance_score` 降序取 Top 3。默认使用用户原始 query 精排，检索使用改写后的关键词。

失败、超时、未配置 Key、非 2xx、解析异常时回退 RRF 前 3 条，标记 `rerank` 并进入 30 秒退避。

### 2.5 生成

[RagConfig](../msd-content-service/src/main/java/com/msd/content/config/RagConfig.java) 装配：

- `rewriteChatClient`：只做查询改写。
- `ragChatClient`：默认挂 `RetrievalAugmentationAdvisor`。
- `ContextualQueryAugmenter`：有上下文/空上下文两套提示，要求只能基于资料、每部给出 1-2 句有依据的理由、不得编造。
- 空上下文模板不能包含 `{query}`：Spring AI 2.0.1 对空上下文模板做无参渲染。

最终返回 `answer` 和最多 `topK` 条 `sources`。回答中的剧名和理由应能在 sources 的标签、简介或剧集中找到依据。

## 3. 索引

| 存储 | 名称 | 说明 |
|---|---|---|
| Milvus collection | `msd_drama_rag_v1` | 1024 维，IVF_FLAT，COSINE，`autoId=false` |
| ES index | `msd_drama_bm25_v1` | 单分片零副本，`dynamic=false`，`jieba_ws` analyzer |

Milvus 2.6.x 的 `common.storageType` 必须为 `remote`；`minio` 已废弃，会触发 Arrow FileSystem 初始化失败并在写入时 panic。`deploy/docker-compose.yml` 已固定为 `remote`。

`POST /internal/ai/rag/reindex` 全量重建：

1. 通过 Feign 分页导出已上架短剧（`deleted=0 AND review_status=2 AND publish_time IS NOT NULL`）。
2. 目录导出失败时不动已有索引。
3. 目录为空且 `allowEmptyCatalog=false` 且已有索引时拒绝清空。
4. Milvus：先删除上一轮和本轮 ID，再 `add`。Spring AI 的 `add` 底层是 insert，不是 upsert。
5. ES：删除并重建 index，逐条 PUT，最后 `_refresh`。
6. 两路互不影响，任一路失败返回 `partial=true` 和 `errors`。
7. `ReentrantLock` 防止并发重建，抢不到返回 409。

## 4. API

### 4.1 推荐

经网关：`POST /api/content/internal/ai/rag/recommend`  
直连：`POST http://127.0.0.1:8107/internal/ai/rag/recommend`

请求：

```json
{"query":"有没有那种女主很虐的剧","topK":3}
```

- `query` 必填，最长 200。
- `topK` 可选 1-3，默认取 `msd.ai.rag.rerank.top-n`。

响应：

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "answer": "《长安第一女仵作》：女主沈知意验尸破案……",
    "rewrittenQuery": "长安 女仵作 验尸 破案 古装 悬疑",
    "sources": [
      {
        "dramaId": 5005,
        "title": "长安第一女仵作",
        "subtitle": "验尸破案，顺便谈个恋爱",
        "coverUrl": "http://127.0.0.1:9000/msd/covers/5005.jpg",
        "snippet": "长安城接连出现离奇命案……",
        "score": 0.93,
        "rrfScore": 0.0328,
        "vectorRank": 1,
        "bm25Rank": 3,
        "retrievalSources": ["vector", "bm25"]
      }
    ],
    "degraded": false,
    "degradedFlags": [],
    "elapsedMs": 3200
  },
  "success": true
}
```

无检索结果：

```text
抱歉，暂时没有找到符合你描述的短剧，可以换一个说法再试试。
```

有来源但生成失败：

```text
这几部短剧和你的需求比较接近，但暂时无法生成推荐理由，可以点开详情看看。
```

两种情况 HTTP 仍为 200，`degraded=true`，通过 `degradedFlags` 区分原因。

### 4.2 重建索引

`POST /internal/ai/rag/reindex`（只允许直连 8107）

```json
{"allowEmptyCatalog": false}
```

响应：

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "dramaCount": 9,
    "episodeCount": 45,
    "vectorUpserted": 54,
    "esIndexed": 54,
    "staleDeleted": 0,
    "vectorAvailable": true,
    "bm25Available": true,
    "partial": false,
    "errors": [],
    "elapsedMs": 2010
  },
  "success": true
}
```

### 4.3 状态

`GET /internal/ai/rag/status`（只允许直连 8107）

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "enabled": true,
    "vectorAvailable": true,
    "bm25Available": true,
    "milvusCollection": "msd_drama_rag_v1",
    "elasticsearchIndex": "msd_drama_bm25_v1",
    "lastIndexedAt": "2026-10-25T06:30:00Z",
    "lastDramaCount": 9,
    "lastEpisodeCount": 45,
    "lastPartial": false,
    "lastError": null
  },
  "success": true
}
```

## 5. 配置

模型配置在 `msd-content-service/src/main/resources/application.yml`：

- `spring.ai.model.chat=deepseek`
- `spring.ai.model.embedding=openai`
- `spring.ai.deepseek.base-url=https://api.deepseek.com`
- `spring.ai.openai.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1`
- `spring.ai.openai.embedding.options.dimensions=1024`
- `spring.ai.retry.max-attempts=2`

RAG 调优参数在 Nacos `content-config.yaml`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `msd.ai.rag.enabled` | `true` | 关闭后 RAG Bean/Controller 不创建 |
| `msd.ai.rag.reindex-on-startup` | `false` | 启动后异步重建 |
| `msd.ai.rag.total-timeout-ms` | `75000` | recommend 整体超时 |
| `msd.ai.rag.rewrite.timeout-ms` | `3000` | 改写超时 |
| `msd.ai.rag.rewrite.failure-backoff-ms` | `30000` | 改写失败退避 |
| `msd.ai.rag.vector.top-k` | `20` | 向量召回数 |
| `msd.ai.rag.vector.timeout-ms` | `3000` | 向量分支超时 |
| `msd.ai.rag.bm25.top-k` | `20` | BM25 召回数 |
| `msd.ai.rag.bm25.timeout-ms` | `2000` | BM25 分支超时 |
| `msd.ai.rag.hybrid.rrf-k` | `60` | RRF 平滑常数 |
| `msd.ai.rag.hybrid.top-k` | `20` | RRF 输出数 |
| `msd.ai.rag.rerank.top-n` | `3` | 精排输出数 |
| `msd.ai.rag.rerank.use-rewritten-query` | `false` | 是否用改写词精排 |
| `msd.ai.rag.rerank.url` | DashScope 地址 | Rerank 接口 |
| `msd.ai.rag.rerank.model` | `qwen3.7-text-rerank` | Rerank 模型 |
| `msd.ai.rag.rerank.api-key` | `${DASHSCOPE_API_KEY:}` | 为空时降级 |
| `msd.ai.rag.rerank.timeout-ms` | `3000` | Rerank 超时 |
| `msd.ai.rag.milvus.*` | `127.0.0.1:19530` | 连接、collection、维度、索引 |
| `msd.ai.rag.elasticsearch.*` | `127.0.0.1:9200` | 地址、index、超时 |
| `msd.ai.rag.catalog.page-size` | `200` | 目录导出分页 |
| `msd.ai.rag.document.max-description-chars` | `1200` | 简介裁剪 |
| `msd.ai.rag.document.max-episode-titles` | `30` | 剧集标题裁剪 |

## 6. 降级行为

| 标记 | 触发条件 | 行为 |
|---|---|---|
| `rewrite` | DeepSeek 超时/异常/空结果 | 用原查询继续检索 |
| `vector` | Milvus 不可用/超时 | 只返回 BM25 结果 |
| `bm25` | ES 不可用/超时/索引不存在 | 只返回向量结果 |
| `rerank` | Key 为空、非 2xx、超时、解析失败、退避 | 回退 RRF Top 3 |
| `generation` | ChatClient 异常/空回答 | 保留 sources，返回固定兜底文案 |
| `timeout` | 超过 `total-timeout-ms` | 返回固定 no-match 文案 |

两路检索都失败时 sources 为空，返回固定 no-match 文案；不会编造剧名。

## 7. 本地验证

单元测试和构建：

```bash
mvn -B -pl msd-content-service -am test
mvn -B -pl msd-content-service -am -DskipTests package
```

覆盖：Document 构建、jieba 分词、ES mapping/检索、RRF、混合检索降级、Rerank 回退、改写回退、推荐响应映射、空上下文模板。

真实基础设施联调：

```bash
docker compose -f deploy/docker-compose.yml up -d etcd minio milvus elasticsearch
python scripts/init-nacos-config.py
# 启动 msd-drama、msd-content-service、msd-gateway
curl -X POST http://127.0.0.1:8107/internal/ai/rag/reindex \
  -H 'Content-Type: application/json' -d '{}'
curl http://127.0.0.1:8107/internal/ai/rag/status
curl 'http://127.0.0.1:9200/msd_drama_bm25_v1/_count'

TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -X POST http://127.0.0.1:8000/api/content/internal/ai/rag/recommend \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"有没有那种先婚后爱的甜宠剧"}'
```

验收点：

- `dramaCount=9`、`episodeCount=45`、`esIndexed=54`、`vectorUpserted=54`、ES `_count=54`（9 部短剧 + 45 集）。
- 连续 reindex 两次，ES 计数仍为 54，Milvus 无重复 ID。
- `sources` 最多 3 条；回答中的剧名和理由能在 sources 中找到依据。
- 停 ES / Milvus / 修改 Rerank Key / 修改 DeepSeek Key 时，`degradedFlags` 分别出现对应标记。
- 无 Token 访问网关 recommend 返回 401；网关访问 reindex/status 返回 404。

本次本地实测（2026-10-25）：

- `msd-content-service` 57 个单元测试通过；`msd-drama` 45 个测试通过；全项目 `mvn -T 1C test` BUILD SUCCESS。
- Nacos/Milvus/Elasticsearch/Seata 正常，`content-config.yaml` 已发布。
- 9 部短剧、45 集分集导出成功，ES `_count=54`；`reindex` 返回 `dramaCount=9`、`episodeCount=45`、`vectorUpserted=54`、`esIndexed=54`、`partial=false`、`vectorAvailable=true`、`bm25Available=true`；连续两次 reindex 后仍为 54。
- 向量 + BM25 + Rerank + 生成通过：`先婚后爱` 命中 5001/5004/5010，`女主验尸破案` 命中 5005，`degradedFlags=[]`，sources 同时带 `vectorRank` 和 `bm25Rank`；无匹配查询返回 grounded 的 no-match。
- 使用有效百炼 Key 后 Rerank 成功：`先婚后爱` 来源 `score` 为 0.9896 / 0.9744 / 0.7095。
- 重启 `msd-milvus` 后再次查询，向量结果仍正常，说明 collection 已持久化。
- ES 停止时 `degradedFlags=["vector","bm25"]`；Milvus 停止时 BM25 仍返回 5005，恢复后正常。
- 网关 6 条路由；无 Token 401；reindex/status 404；user/drama/order/interaction 旧路由回归 200。
- **Milvus 配置修复**：此前 `COMMON_STORAGETYPE=minio` 导致 Arrow FS 初始化失败并触发 `MmapManager` / `Failed to get filesystem` panic。改为 `COMMON_STORAGETYPE=remote` 后，2.6.4 使用原 `milvus_data` 数据卷直接恢复，无需删库或换镜像；2.5.7/2.6.7 的尝试已不再需要。

## 8. 已知限制

- 只做全量重建，不做 Kafka 实时增量索引。
- 不做用户画像、观看历史个性化排序。
- 前端没有独立 RAG 推荐页，当前只提供后端 API。
- 现有 `msd-agent-service` 的 Mock RAG 保留，不替换；A2A/Supervisor 未接入本 RAG。
- 本地 ES 关闭安全认证，仅绑定 `127.0.0.1`；生产需要额外鉴权和网络隔离。
- Milvus 未启用认证，生产需要补齐。
