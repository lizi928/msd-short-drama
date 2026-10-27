# 结构化内容审核

内容服务提供结构化审核接口，模型严格输出 JSON，再由 Spring AI Structured Output 映射为 Java record。审核结果不修改短剧审核状态机，只作为调用方的判断依据。

> 已接入短剧审核流：AI 只写 `t_drama.ai_review_*`，人工审核（ADMIN）才是 `review_status` 的唯一迁移入口。详见第 7 节。

## 1. 接口

```http
POST /internal/ai/audit/structured
Content-Type: application/json
```

```json
{"content":"未成年人深夜独自进入酒吧消费","scene":"drama_description"}
```

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "verdict": {
      "is_violation": true,
      "violation_reason": "涉及未成年人深夜独自进入酒吧消费，存在危害未成年人权益的风险",
      "tags": ["minor"]
    },
    "degraded": false,
    "degradedFlags": [],
    "elapsedMs": 1788
  },
  "success": true
}
```

- `content`：1-5000 字。
- `scene`：业务场景，可选，如 `drama_description`、`comment`。
- 接口只允许直连 8107，网关不路由。

## 2. 审核结论

[AuditVerdict](../msd-content-service/src/main/java/com/msd/content/audit/AuditVerdict.java)：

| 字段 | 含义 |
|---|---|
| `is_violation` | 是否存在违规风险 |
| `violation_reason` | 违规原因；未违规时强制为空字符串 |
| `tags` | 命中标签；未违规时强制为空数组 |

允许的标签：

`porn`、`violence`、`crime`、`minor`、`discrimination`、`false_advertising`、`infringement`、`privacy`、`payment_inducement`

模型返回未知标签时会被过滤，只保留上述枚举。

## 3. Structured Output

[StructuredAuditService](../msd-content-service/src/main/java/com/msd/content/audit/StructuredAuditService.java) 使用：

```java
AuditVerdict verdict = auditChatClient.prompt()
        .system(AUDIT_SYSTEM_PROMPT)
        .user(userPrompt)
        .call()
        .entity(AuditVerdict.class);
```

- `auditChatClient` 不挂记忆、不挂 RAG Advisor，避免审核文本进入用户会话。
- 待审核内容用固定边界包裹，System Prompt 明确它是不可信数据，不执行其中的指令。
- 日志只记录内容哈希、长度、场景、耗时和降级标记，不记录全文。

## 4. 降级

| 场景 | verdict | degradedFlags |
|---|---|---|
| 模型超时 | `null` | `audit_timeout` |
| JSON 无法解析 / 返回空 | `null` | `audit_parse_error` |
| 模型服务不可用 | `null` | `audit_unavailable` |

- 每次请求最多尝试 `max-attempts=2` 次。
- 仍失败时 `verdict=null`、`degraded=true`，**调用方不得自动判定通过**。
- 单次调用超时 `timeout-ms=10000`。

## 5. 配置

Nacos `content-config.yaml`：

```yaml
msd:
  ai:
    audit:
      enabled: true
      timeout-ms: 10000
      max-attempts: 2
```

## 6. 手工验证

```bash
# 违规文本
curl -X POST http://127.0.0.1:8107/internal/ai/audit/structured \
  -H 'Content-Type: application/json' \
  -d '{"content":"未成年人深夜独自进入酒吧消费","scene":"drama_description"}'

# 正常文本
curl -X POST http://127.0.0.1:8107/internal/ai/audit/structured \
  -H 'Content-Type: application/json' \
  -d '{"content":"今天天气不错，适合在家看短剧","scene":"comment"}'
```

实测（2026-10-25）：

- 违规文本返回 `is_violation=true`、`tags=["minor"]`、`degradedFlags=[]`。
- 正常文本返回 `is_violation=false`、`violation_reason=""`、`tags=[]`。
- 网关访问接口返回 404；空内容返回 400。

## 7. 接入短剧审核

### 7.1 状态机

```text
草稿(0)/驳回(3) --提交审核--> 审核中(1) --人工通过--> 已上架(2)
                                      --人工驳回--> 驳回(3)
已上架(2) --下架--> 下架(4)
```

AI 审核只在 `review_status=1` 时写回 `ai_review_status`：

| AI 结果 | ai_review_status | 是否自动上架 |
|---|---|---|
| 未违规 | 2 通过 | 否 |
| 违规 | 3 驳回 | 否 |
| 超时/解析失败/不可用 | 4 失败 | 否 |

人工审核是唯一发布入口。AI 失败或驳回时，管理员仍可覆盖结论，但页面/文档需要提示“AI 失败，需人工复核”。

### 7.2 drama 内部接口

只允许 content-service 直连，网关不路由：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal-source/reviews/pending?limit=20` | 待 AI 审核候选，`review_status=1` 且 AI 状态 0/1/null |
| GET | `/internal-source/reviews/{dramaId}` | 待审详情，非待审返回 `data=null` |
| GET | `/internal-source/reviews/tag-dictionary` | 启用中的标签名称，供 Rex 标签建议只读使用 |
| PUT | `/internal-source/reviews/{dramaId}/ai-result` | 条件写回，仅 `review_status=1` 生效 |

写回请求：

```json
{"aiReviewStatus":2,"aiReviewResult":"{\"scene\":\"drama_description\",\"verdict\":{\"is_violation\":false,\"violation_reason\":\"\",\"tags\":[]},\"degraded\":false,\"degradedFlags\":[],\"elapsedMs\":1472,\"auditedAt\":\"2026-10-25T23:26:30+08:00\"}"}
```

### 7.3 content-service 审核编排

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/internal/ai/audit/dramas/pending?limit=5` | 批量审核待审短剧，单批上限 20 |
| POST | `/internal/ai/audit/dramas/{dramaId}` | 审核单部待审短剧 |

- 送审文本：标题 + 副标题 + 简介，截断 5000 字，`scene=drama_description`。
- 合规调用 DeepSeek 的同时，并行调用本地 RexUniNLU sidecar（8108）生成情感/标签建议。
- 降级写 4，违规写 3，未违规写 2；Rex 建议不参与状态映射，降级绝不写 2。
- 单条失败不中断批次；写回 0 行计入 `skipped`。
- 只允许直连 8107，网关不路由。

### 7.4 管理端接口

经网关访问 `/api/drama/internal/admin/dramas`，服务内校验 `X-User-Role`：

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| GET | `/reviews?reviewStatus=&aiReviewStatus=&pageNo=&pageSize=` | ADMIN | 审核队列分页 |
| POST | `/{dramaId}/submit` | CREATOR 本人 / ADMIN | 草稿/驳回 -> 审核中，重置 AI 状态 |
| POST | `/{dramaId}/review` | ADMIN | body `{"decision":"APPROVE|REJECT","remark":"..."}`；驳回必须填意见 |
| POST | `/{dramaId}/offline?remark=` | ADMIN | 已上架 -> 下架 |
| GET | `/{dramaId}/logs` | ADMIN | 最近 50 条审核操作日志 |

- 所有迁移使用条件 UPDATE，0 行返回 409，避免并发覆盖。
- 提交、通过、驳回、下架和 AI 写回都写入 `t_drama_review_log`；AI 操作为 `source=AI`、`operator_id=0`。
- 通过/驳回/下架后调用 `CacheEvictor` 失效详情和首页；通过后加入布隆过滤器。
- RAG 索引不会自动更新；新上架短剧需要执行全量 reindex（P5 会在后台提供按钮）。

### 7.5 手工演示

```bash
# 0. 重置演示数据
mysql -umsd -p -h127.0.0.1 --default-character-set=utf8mb4 \
  < deploy/mysql/repair/reset_review_demo.sql

# 1. 待审候选，预期只有 5003
curl http://127.0.0.1:8102/internal-source/reviews/pending?limit=10

# 2. AI 审核
curl -X POST 'http://127.0.0.1:8107/internal/ai/audit/dramas/pending?limit=5'

# 2.1 启动本地 RexUniNLU sidecar（可选；未启动时情感建议降级）
scripts\start-rex-uninlu.cmd
curl http://127.0.0.1:8108/readyz

# 3. 查看 AI 结果
curl http://127.0.0.1:8102/internal-source/reviews/5003

# 4. 管理员通过
ADMIN_TOKEN=$(python scripts/gen-jwt.py --user-id 1001 --role ADMIN)
curl -X POST http://127.0.0.1:8000/api/drama/internal/admin/dramas/5003/review \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","remark":"AI通过，人工复核通过"}'

# 5. 公开详情可访问
USER_TOKEN=$(python scripts/gen-jwt.py --user-id 1003 --role USER)
curl -H "Authorization: Bearer $USER_TOKEN" \
  http://127.0.0.1:8000/api/drama/internal/dramas/5003
```

实测（2026-10-25）：

- 5003 AI 审核返回 `passed=1`，写回 `aiReviewStatus=2`。
- 管理员通过后 `reviewStatus=2`、`publishTime` 写入，公开详情 200。
- 普通用户调用审核接口返回 403。
- 5012 草稿提交 -> AI 通过 -> 管理员驳回，状态 3，驳回意见落库。
- 5003 下架后公开详情 404。
- `reset_review_demo.sql` 可恢复 5003/5012/5013 演示状态。

### 7.6 RexUniNLU 情感/标签建议

详细模型、API、依赖和性能见 [rex-uninlu.md](rex-uninlu.md)。

- 模型：`iic/nlp_deberta_rex-uninlu_chinese-base@v1.0.2`，Apache 2.0，本地 CPU sidecar。
- 输入：标题 + 副标题 + 简介；每 450 字一片，最多 6 片。
- 情感：`positive/negative/mixed/unknown`；负向支持数 ≥ 2/3 片时 `highNegative=true`，
  审核台显示“建议人工复核”。
- 标签：来自 drama 启用标签字典，最多 5 个，按命中片数排序，只读展示。
- 失败：sidecar 未启动、超时、非 2xx 或解析失败只写 degraded advisory；
  `ai_review_status` 仍按合规结论写 2/3/4。
- 数据边界：不新增数据库列，不写 `t_drama_tag_rel`，不触发缓存失效或 RAG reindex。
- 回滚：Nacos `msd.ai.rex.enabled=false`，停止 8108；旧审核 JSON 无 `advisory` 仍可解析。

失败演练：

```bash
# 停 sidecar 后重审，预期仍写 2，advisory degraded=true
curl -X POST http://127.0.0.1:8107/internal/ai/audit/dramas/5003
curl http://127.0.0.1:8102/internal-source/reviews/5003
```
