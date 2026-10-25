# 创作者工作台与审核操作

创作者可以创建草稿短剧、编辑剧集、提交审核；管理员在审核台查看 AI 结果、通过/驳回、下架和重建索引。仅草稿或驳回状态可编辑，已上架内容需先下架。

## 1. 创作者接口

经网关访问，路径前缀 `/api/drama/internal/creator/dramas`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 分页查询我的短剧 |
| GET | `/{dramaId}` | 短剧详情和剧集，供编辑页回填 |
| POST | `/` | 创建草稿短剧和剧集 |
| PUT | `/{dramaId}` | 编辑草稿或驳回短剧 |
| DELETE | `/{dramaId}` | 软删除草稿或驳回短剧 |
| POST | `/{dramaId}/submit` | 提交审核 |

创建/编辑请求：

```json
{
  "title": "雾城谜案",
  "subtitle": "每一通深夜来电，都藏着一个秘密",
  "coverUrl": "",
  "description": "刑警追查十年前旧案。",
  "categoryId": 2003,
  "actors": "赵远,许晴",
  "episodes": [
    {"episodeNo": 1, "title": "第1集 深夜来电", "durationSeconds": 135, "pricePoints": 0, "status": 1},
    {"episodeNo": 2, "title": "第2集 失踪", "durationSeconds": 150, "pricePoints": 20, "status": 1}
  ]
}
```

- 作者固定为当前用户；`CREATOR` 只能操作自己的短剧，`ADMIN` 不限。
- 剧集按 `episode_no` upsert；本次未提交的旧剧集置为下线，不物理删除。
- `video_url` 固定为空字符串，Demo 不存真实视频。
- 编辑后失效详情/首页缓存。

## 2. 管理端

| 方法 | 路径 | 角色 |
|---|---|---|
| GET | `/api/drama/internal/admin/dramas/reviews?reviewStatus=&aiReviewStatus=&pageNo=&pageSize=` | ADMIN |
| POST | `/api/drama/internal/admin/dramas/{id}/review` | ADMIN |
| POST | `/api/drama/internal/admin/dramas/{id}/offline` | ADMIN |
| GET | `/api/drama/internal/admin/dramas/{id}/logs` | ADMIN |
| POST | `/api/content/internal/ai/audit/dramas/{id}` | ADMIN（经网关） |
| POST | `/api/content/internal/ai/rag/reindex` | ADMIN（经网关） |

- 审核通过/驳回/下架使用条件 UPDATE，冲突返回 409。
- 通过后失效缓存、加入布隆过滤器；新上架短剧需要重建 RAG 索引。

## 3. 审核操作日志

`t_drama_review_log` 记录：

| 字段 | 说明 |
|---|---|
| `action` | SUBMIT / APPROVE / REJECT / OFFLINE / AI_REVIEW |
| `from_status` / `to_status` | 人工审核状态或 AI 审核状态 |
| `operator_id` | 人工操作人；AI 为 0 |
| `source` | HUMAN / AI |
| `remark` | 审核意见 |

AI 写回和人工状态迁移在同一套日志里，审核台可查看最近 50 条。

## 4. 前端

| 路由 | 页面 | 角色 |
|---|---|---|
| `/creator/dramas` | 我的短剧 | CREATOR / ADMIN |
| `/creator/dramas/new` | 新建短剧 | CREATOR / ADMIN |
| `/creator/dramas/:id/edit` | 编辑短剧与剧集 | CREATOR / ADMIN |
| `/admin/reviews` | 审核台：AI 审核、通过/驳回/下架、日志、重建索引 | ADMIN |

个人中心根据角色显示“创作者中心”“审核台”“评论治理”入口；后端仍做最终权限校验。

## 5. v1 边界

- 不做富文本、素材库、视频上传、标签管理页、审核撤回。
- 已上架内容不能直接编辑，需先下架。
- 重建索引是全量操作，运行期间可能短暂影响推荐结果。
