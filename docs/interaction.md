# 互动：点赞、收藏、评论与观看历史

`msd-interaction` 负责点赞、收藏、评论和观看历史的读写。写接口沿用 outbox + Kafka 更新 drama 侧冗余计数；列表读接口直接查 interaction 库，不依赖 Kafka。

所有接口都需要登录，经网关访问：

```text
/api/interaction/internal/interactions/**
```

## 1. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/comments?dramaId=&episodeId=&pageNo=&pageSize=` | 根评论分页 |
| GET | `/comments/{rootId}/replies?pageNo=&pageSize=` | 某根评论的回复分页 |
| GET | `/comments/mine?pageNo=&pageSize=` | 我的评论 |
| POST | `/comments` | 创建评论/回复 |
| DELETE | `/comments/{commentId}` | 删除自己的评论 |
| POST | `/likes` | 点赞短剧/剧集/评论 |
| DELETE | `/likes/{targetType}/{targetId}` | 取消点赞 |
| POST | `/favorites` | 收藏短剧 |
| DELETE | `/favorites/{dramaId}` | 取消收藏 |
| GET | `/favorites?pageNo=&pageSize=` | 我的收藏 |
| GET | `/likes/dramas?pageNo=&pageSize=` | 我点赞的短剧 |
| GET | `/dramas/{dramaId}/state` | `{liked, favorited}` |
| GET | `/history?pageNo=&pageSize=` | 观看历史 |
| POST | `/history` | 上报观看进度 |
| DELETE | `/history/{episodeId}` | 删除单条历史 |
| DELETE | `/history` | 清空全部历史 |
| GET | `/stats` | 个人中心互动计数 |

## 2. 评论

### 2.1 列表规则

- `dramaId` 必填；传 `episodeId` 查剧集评论，不传查 `episode_id IS NULL` 的短剧级评论。
- 根评论只返回 `status=1` 且 `root_id=0`，按 `create_time DESC, id DESC`。
- 回复只返回 `status=1`，按 `create_time ASC, id ASC`；根评论不存在/不可见返回 404。
- 根评论带 `replyCount`，回复单独分页。
- 每条评论补齐作者昵称/头像、`replyToNickname` 和当前用户 `liked`。

`CommentView`：

```json
{
  "commentId": 8301,
  "dramaId": 5001,
  "episodeId": 6001,
  "userId": 1003,
  "nickname": "追剧用户",
  "avatarUrl": "",
  "rootId": 0,
  "parentId": 0,
  "replyToUserId": null,
  "replyToNickname": null,
  "content": "……",
  "likeCount": 1,
  "replyCount": 1,
  "liked": false,
  "createTime": "2026-10-01T20:00:00"
}
```

### 2.2 创建校验

- 内容 trim 后不能为空。
- 新根评论：`rootId=0`、`parentId=0`。
- 回复：根评论必须存在、可见、是根评论，且与请求的 `dramaId/episodeId` 一致。
- 回复某个回复时，父评论必须属于同一个根评论。
- 服务端写入 `reply_to_user_id`，用于 `@用户` 展示。
- 不合法返回 400/404，不再允许跨短剧或跨根评论拼接回复。

### 2.3 删除

- 只有作者可以删除；不存在返回 404「评论不存在」，非作者返回 403「无权删除该评论」。
- 删除回复：软删除、根评论 `reply_count-1`、写一条 `COMMENT_DELETED`。
- 删除根评论：级联软删除所有可见回复，根和每条回复各写一条 `COMMENT_DELETED`，保证 drama/episode 评论数按条递减。
- 已删除评论再次删除返回 404。

### 2.4 评论点赞

- 复用 `/likes`，`targetType=3`。
- 只允许点赞 `status=1` 的评论；已删除/隐藏评论返回 404。
- `t_comment.like_count` 在 interaction 本地同步更新；评论列表返回当前用户 `liked`。

## 3. 收藏、点赞短剧与观看历史

### 3.1 收藏

`GET /favorites` 返回：

```json
{
  "dramaId": 5001,
  "title": "隐婚总裁的甜妻",
  "subtitle": "……",
  "coverUrl": "http://...",
  "episodeCount": 6,
  "favoriteTime": "2026-10-01T20:00:00"
}
```

按收藏时间倒序。短剧未上架时仍保留条目，标题/封面为 null。

### 3.2 点赞短剧

`GET /likes/dramas` 只返回 `target_type=1` 的点赞，按点赞时间倒序；剧集/评论点赞不进个人中心列表。

### 3.3 观看历史

`GET /history` 返回：

```json
{
  "dramaId": 5001,
  "dramaTitle": "隐婚总裁的甜妻",
  "dramaCoverUrl": "http://...",
  "episodeId": 6003,
  "episodeNo": 3,
  "episodeTitle": "第3集 危机",
  "episodeCoverUrl": "http://...",
  "positionMs": 150000,
  "durationMs": 150000,
  "finished": true,
  "lastWatchTime": "2026-10-21T21:00:00"
}
```

- 按 `last_watch_time DESC, id DESC`。
- 删除/清空历史只物理删除历史行，不回退播放量。
- 历史上报仍复用 `POST /history`；只有首次插入写 `HISTORY` 事件，播放量只在首次观看增加。

## 4. 分页与跨服务补齐

### 4.1 分页

- `pageNo` 默认 1、最小 1；`pageSize` 默认 20、最大 100。
- 返回 `PageResult`：`{pageNo, pageSize, total, records}`。
- 使用手写 `COUNT + LIMIT/OFFSET`，没有引入 MyBatis-Plus 分页插件。

### 4.2 批量补齐

- 评论作者：`POST /api/user/internal/users/batch`。
- 收藏/历史/点赞/我的评论：`POST /api/drama/internal/dramas/batch`、`POST /api/drama/internal/episodes/batch`。
- 每次最多 100 个 ID；评论页作者 + 回复目标可能超过 100，服务端按 100 分片。
- 批量接口失败时只记录 warning，列表继续返回，缺失的标题/昵称降级为 null，不把列表打成 503。

## 5. 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | 40000 | 参数校验失败、评论 root/parent 不匹配、请求体格式错误 |
| 401 | 40100 | 未登录 |
| 403 | 40300 | 删除他人评论 |
| 404 | 40400 | 评论/根评论不存在，已删除，批量 ID 为空等 |
| 429 | 42900 | 网关限流 |
| 500 | 50000 | 未预期异常 |

## 6. 验收记录（2026-10-24）

环境：MySQL、Nacos、msd-user 8101、msd-drama 8102、msd-interaction 8104、msd-gateway 8000；Kafka 未启动。

| 用例 | 结果 |
|---|---|
| 用户批量 `[1003,1006,999999]` | 返回 1003、1006 |
| 短剧批量 `[5001,5003,999999]` | 只返回已上架 5001 |
| 剧集批量 `[6001,6201,999999]` | 返回 6001、6201（6201 下线但仍存在） |
| 5001/6001 评论 | 8301，作者 1003，replyCount=1 |
| 8301 回复 | 8302，作者 1002，replyTo=1003 |
| 5004 短剧级评论 | 只有 8314；status=0 的 8318 不出现 |
| 5004/6301 评论 | 8310，作者 1006，likeCount=2 |
| 我的评论 | 8301、8314；status=3 的 8326 不出现 |
| 收藏/点赞短剧 | 5001；state 5001 = liked/favorited |
| 历史 | 4 条，标题/集数补齐，按 last_watch_time 倒序 |
| 统计 | likedDramaCount=1、favoriteCount=1、commentCount=2、historyCount=4 |
| 分页 | pageNo=0 归 1，pageSize=200 归 100；pageNo=2&pageSize=2 返回后两条 |
| 评论创建/回复 | 新根 8327，回复 8328/8329；replyToUserId 正确，reply_count=2 |
| 非法回复 | root 不存在 404；root 与短剧不匹配 400；父评论不属于 root 400 |
| 根评论级联删除 | 8327/8328/8329 全部 status=3；outbox 3 条 COMMENT_DELETED |
| 非作者删除 | 1006 删除 8301 → 403「无权删除该评论」 |
| 评论点赞 | 8301 like_count 1→2，列表 liked=true；取消后恢复 |
| 收藏/点赞 5005 | 列表出现；取消后 state 恢复 false |
| 历史删除/清空 | 测试用户 2 条 → 删除 1 条 → 清空 0 条 |
| 批量 101 个 ID | 400「一次最多查询 100 个 ID」 |
| 非法 JSON | 400「请求体格式不正确」 |
| 资料编辑 | 昵称/头像/简介更新成功；空昵称、超长昵称 400；已恢复 1003 原资料 |

测试评论已按 `[slice-test]%` 清理，测试用户和历史已删除，outbox/consume log 已用复位脚本清理。

## 7. 不在本切片

- 评论 AI 预审、敏感词、评论编辑、评论恢复、评论点赞列表；管理员隐藏/删除见 [moderation.md](moderation.md)。
- 按短剧聚合的「继续观看」已接入 `GET /history/continue`，首页显示最近一条。
- 头像上传/裁剪；只接受 URL 字符串。
- 点赞/收藏写入的短剧/剧集存在性校验（列表补齐时自然隐藏）。
- Redis 缓存、通知、消息中心。
