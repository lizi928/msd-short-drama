# 前端应用

`frontend/` 是 Vue 3 + Vite 单页应用，移动优先，内容区最大宽度 480px。开发时通过 5173 端口访问，`/api` 代理到网关 8000；生产构建产物由网关静态托管。

## 1. 技术栈与运行

- Vue 3.5、vue-router 4、Pinia 3、Vite 7，纯 JavaScript。
- 不引入 UI 库、axios、TypeScript；图标全部内联 SVG。
- 封面不请求远程图片，播放不加载真实视频。

```bash
cd frontend
npm install
npm run dev      # http://127.0.0.1:5173
npm run build    # 输出 frontend/dist
```

演示账号：`13800000003 / Msd@123456`（用户 1003）。种子基线为余额 250、已解锁 6003/6005；后续人工播放/解锁会改变当前数据。

## 2. 路由

| 路径 | 页面 | 布局 |
|---|---|---|
| `/login` | 登录 | 公开 |
| `/register` | 注册 | 公开 |
| `/` | 首页 | 底部 Tab 首页 |
| `/history` | 追剧记录 | 底部 Tab 追剧 |
| `/search` | 搜索/分类发现 | 子页 |
| `/me` | 个人中心 | 底部 Tab 我的 |
| `/me/favorites` | 我的收藏 | 子页 |
| `/me/comments` | 我的评论 | 子页 |
| `/me/likes` | 点赞的短剧 | 子页 |
| `/recharge` | 充值中心 | 子页 |
| `/me/unlocks` | 已解锁剧集 | 子页 |
| `/creator/dramas` | 创作者中心 | 子页，CREATOR/ADMIN |
| `/creator/dramas/new` | 新建短剧 | 子页，CREATOR/ADMIN |
| `/creator/dramas/:id/edit` | 编辑短剧 | 子页，CREATOR/ADMIN |
| `/admin/reviews` | 审核台 | 子页，ADMIN |
| `/admin/comments` | 评论治理 | 子页，ADMIN |
| `/drama/:dramaId` | 红果式全屏 Feed | 沉浸式 |
| `/watch/:episodeId` | 旧播放入口，解析后渲染同一 Feed | 沉浸式 |
| 其他 | 404 | 公开 |

- 除登录、注册、404 外都要求有效会话；未登录跳转 `/login?redirect=<原路径>`。
- 注册成功同样支持 `redirect` 回跳。
- 任意接口 401 触发全局清理，并行请求只跳转一次。
- `/drama/:dramaId?episode=<id>&start=<毫秒>` 直接定位某集；`start` 从观看历史续播。
- 切集不写 query，避免 `RouterView :key="route.fullPath"` 重挂载。

## 3. 页面与接口

| 页面 | 接口 |
|---|---|
| 登录/注册 | `POST /api/user/auth/login`、`/register` |
| 首页 | `GET /api/drama/internal/home` |
| 搜索/分类 | `GET /api/drama/internal/categories`、`GET /api/drama/internal/search/dramas` |
| 短剧 Feed | `GET /api/drama/internal/dramas/{id}`、`GET /api/interaction/internal/interactions/dramas/{id}/state`、`GET /api/order/internal/orders/dramas/{id}/unlocks`、`GET /orders/points`、`GET /playback/episodes/{id}` |
| 播放信息 | `GET /api/order/internal/playback/episodes/{id}` |
| 积分解锁 | `GET /api/order/internal/orders/points`、`POST /api/order/internal/orders/episodes/{id}/unlock` |
| 充值中心 | `GET /recharge/plans`、`POST /recharge/orders`、`POST /recharge/orders/{id}/mock-pay`、`GET /orders/points/ledger` |
| 点赞/收藏 | `POST/DELETE /api/interaction/internal/interactions/likes`、`/favorites` |
| 评论 | `GET/POST /comments`、`GET /comments/{rootId}/replies`、`DELETE /comments/{id}` |
| 观看历史 | `GET/POST /history`、`DELETE /history/{episodeId}`、`DELETE /history` |
| 个人中心 | `GET/PUT /api/user/internal/users/me`、`GET /orders/points`、`GET /interactions/stats` |
| 四个列表 | `/favorites`、`/history`、`/comments/mine`、`/likes/dramas` |

- 所有业务接口统一返回 `Result`，`api/http.js` 负责解包、附加 Bearer Token 和抛出 `ApiError(code, message, status)`。
- Token 与用户资料存放在 `localStorage` 的 `msd.auth`，不使用 Cookie。
- 分页统一 `pageNo/pageSize`，页面采用“加载更多”，不做页码跳转。
- 列表数据由后端批量补齐；标题或昵称为空时前端降级显示，不阻断列表。

### 3.1 搜索与分类

- 首页头部“搜索短剧、演员、剧情”入口跳转 `/search`。
- 搜索页支持关键词（≤50 字）、分类 chips、最新/最热切换和分页加载；分类接口失败时隐藏 chips，不阻断搜索。
- 关键词只在提交时生效；再次点击已选分类会取消筛选。
- 结果复用 `DramaCard` 卡片和 `usePagedList`，空结果显示 `EmptyState`。
- 后端只返回已上架短剧；Demo 使用 MySQL LIKE，不做搜索历史、热词和语义搜索。

## 4. 封面与视觉

- `GradientCover.vue` 用 `dramaId`/`episodeId` 做 FNV-1a hash，映射为固定色相、角度、光斑和水印字，同一个对象每次渲染颜色一致。
- 首页卡片、Banner、Feed 全屏背景、历史/收藏缩略图、播放器舞台全部使用渐变封面，**不渲染 `coverUrl` 图片**，因此 MinIO 未启动也不会出现破图。
- `UserAvatar` 优先加载 `avatarUrl`，失败或为空时回退昵称首字渐变头像。
- 视觉沿用暗色 + 橙金品牌色；页面默认按 360–480px 设计，桌面居中单列。

## 5. 全屏 Feed 与占位播放器

- 点开短剧直接进入 `/drama/:dramaId` 全屏 Feed，首集自动播放；顶栏常驻返回、剧名和 `当前集/总集数`。
- 每集一个全屏 slide，原生 `scroll-snap-type: y mandatory` 上下滑动切集；最后一集后是“已看完”结束卡，可返回首页或重看第 1 集。
- 右侧操作栏：点赞、收藏、评论、伴看、分享（复制链接）；底部显示集号/标题、剧名、简介和“选集”入口。
- “伴看”打开 AI 伴看面板，仅当前集免费或已解锁时可用；消息和 `sessionId` 存 `sessionStorage`，离开再进入可继续本次对话。
- 点赞/收藏为抖音式动态按钮：点击激活时图标回弹、粒子与光环爆发、计数跳动；激活后点赞为红色实心爱心、收藏为金色实心星星，取消恢复白色描边。动画只由点击触发，加载已有互动状态不播放。
- 简介/选集/评论均为底部弹层；评论弹层支持“本集 / 全剧”切换。打开弹层时暂停当前集并上报进度，关闭后恢复原播放状态。
- `/watch/:episodeId` 只做 episode → dramaId 解析，然后渲染同一 Feed；新链接优先使用 `/drama/:dramaId?episode=`。
- 不使用 `<video>`，不读取 `videoUrl`；播放画面是渐变舞台 + 中央播放按钮 + 底部进度控件。
- 时长来自播放接口的 `durationSeconds`（毫秒）；使用 `requestAnimationFrame` + 单调时间差推进，页面隐藏自动暂停。
- 每个 slide 独立持有播放器和上报器；失活 slide 立即暂停并 flush，重新激活时恢复播放。
- 未解锁不启动模拟播放，只展示价格、余额和解锁按钮；解锁成功后重新请求播放信息，确认 `unlocked=true` 才播放。
- 新用户没有积分账户时按钮禁用并提示；积分不足显示差额。

### 5.1 观看历史上报

- 只有 `free || unlocked` 且用户开始播放后才启用上报；每个 slide 独立上报器。
- 每累计播放 5 秒上报一次；暂停、拖动结束、切集失活、页面隐藏、路由离开、`pagehide` 时补报。
- 上报串行化，期间的新位置合并为最新快照，避免旧请求后到覆盖新位置。
- 播放到 95% 进入完成态，停止计时并只发送一次 `finished=true`。
- **完成后锁定上报器**：重播只重置本地画面，不再写非完成状态。原因是 `POST /history` 是 upsert，每次都会用请求里的 `finished` 覆盖旧值，完成后再发 `finished=false` 会把记录改回未完成。
- 离开页面时优先等待队列清空（最多约 1.2 秒），`pagehide` 使用 `fetch keepalive`；不使用 `sendBeacon`，因为无法附加 `Authorization`。
- 锁屏状态绝不上报历史。

## 6. 评论

- Feed 右侧评论按钮打开底部弹层，顶部可在“本集 / 全剧”之间切换：本集传 `episodeId`，全剧不传。
- 弹层打开时 v-if + key 挂载 `CommentSection`，切集后重新打开会按新集请求，不会复用旧列表。
- 根评论按时间倒序，每页 10 条；回复首次展开时才请求，按时间正序，每页 10 条。
- 支持发布根评论、回复根评论、回复某条回复；服务端写入 `reply_to_user_id`，前端展示“回复 @昵称”。
- 评论点赞走 `targetType=3`，本地乐观更新，失败回滚。
- 仅作者可删除；删根评论会级联删除回复，前端确认框会提示。
- 发布/回复成功后本地插入新评论并同步根评论 `replyCount`，不整页刷新。
- 评论内容上限 1000 字，输入框实时计数。

## 7. 个人中心

- 进入页面并行请求 `GET /me`、积分、统计；资料失败时保留本地缓存，积分/统计各自显示重试。
- 四项统计可点击进入对应列表；下方菜单提供同样的四个入口。
- 资料编辑使用底部弹层，只提交昵称/头像 URL/简介；空头像与空简介表示清空。
- 昵称 1–20 字、头像 URL ≤512、简介 ≤255，前后端校验一致。
- 收藏/点赞列表支持取消；观看历史支持删除单条和清空；我的评论支持删除。
- `1003` 的基线统计为 `1/1/2/4`。

## 8. 构建与网关托管

开发：

```bash
cd frontend && npm run dev
```

- `vite.config.js` 把 `/api` 代理到 `http://127.0.0.1:8000`，需要网关和业务服务已启动。

生产：

```bash
cd frontend && npm run build
cd ..
mvn -B -pl msd-gateway -am package -DskipTests
java -jar msd-gateway/target/msd-gateway-1.0.0-SNAPSHOT.jar
```

- 网关在 `process-resources` 阶段把 `frontend/dist` 复制到 `target/classes/static`。
- 修改前端后必须重新构建前端并重新打包网关，仅重启网关不会更新页面。
- `SpaWebFilter` 把非 `/api`、`/actuator`、`/internal`、`/console` 且没有扩展名的 HTML 请求重写到 `index.html`，因此 `/drama/5001`、`/me/favorites` 可以直接刷新。
- 静态资源请求不经过网关 JWT 过滤器（过滤器只作用于命中的 `/api` 路由），页面本身可以在登录前加载。
- 接口 401 仍由网关返回统一 JSON，前端收到后跳登录。

## 9. 已知限制

- 没有真实视频播放、封面图片、视频签名 URL；MinIO 未启动不影响页面。
- Feed 只在本剧剧集间上下滑动，不跨短剧连播；最后一集后进入结束卡。
- 搜索使用 MySQL LIKE，数据量上来后需要改 FULLTEXT/ES；不做个性化推荐、搜索历史、热词、拼音纠错和语义搜索。
- 首页分类 chips 仍只筛选已加载的热播/最新内容；完整分类浏览在 `/search`。
- 充值中心、积分流水、已解锁列表、创作者工作台、审核台和评论治理已接入；没有头像上传、退款；新用户充值支付时自动创建积分账户。
- 没有评论编辑、审核、敏感词、点赞列表。
- Kafka 未启动时短剧/剧集/评论冗余计数会滞后，页面展示接口返回值，不自行重算。
- 列表不做 KeepAlive，返回列表会重新请求第一页。
- 不引入 ESLint、单测、E2E 框架；构建通过 + 手工/无头浏览器验收。

## 10. 验收记录（2026-10-24，Feed 改造）

环境：MySQL、Nacos、Seata、msd-user 8101、msd-drama 8102、msd-order 8103、msd-interaction 8104、msd-gateway 8000；Kafka/Redis 已恢复可达；Vite 5173。

| 用例 | 结果 |
|---|---|
| `npm run build` | 通过，118 个模块，产物在 `frontend/dist` |
| `/drama/5001` | 7 个 slide（6 集 + 结束卡），顶栏 1/6，首集自动播放 |
| 首屏请求 | 只请求当前集 playback，未请求其他集 |
| 滚轮 / 触摸上滑 | 1/6 → 2/6；旧集暂停，新集播放；`inert` 不阻断滚动 |
| 页面滚动锁 | Feed 内 `body.overflow=hidden`；离开后恢复为空，底部 Tab 恢复 |
| 锁定集 6006 | 显示价格 50、余额 100、解锁按钮；无播放器 |
| 临时用户解锁 6006 | 余额 100→50，`unlocked=true`，播放器出现并自动播放 |
| 历史上报 | 6401 首次 5983ms；滑到 6402 后 6401 flush 到 9500ms；离开后 6402 为 7850ms |
| 简介弹层 | 显示封面、标签、演员、简介和计数 |
| 选集弹层 | 6 集，免费/已解锁/积分角标正确；选择后定位到对应集 |
| 评论弹层 | 5004 全剧显示 8314；本集/全剧切换正常；打开时播放暂停，关闭恢复 |
| 点赞/收藏动态按钮 | 点击后图标回弹、10 个粒子 + 光环爆发、计数跳动；取消恢复白色描边；加载已有状态不触发动画 |
| 结束卡 | 第 6 集后显示“已看完本剧”，重看/返回首页可用 |
| 旧入口 `/watch/6001` | 解析后进入同一 Feed，定位第 1 集 |
| 深链 `/drama/5001?episode=6002` | 直接定位第 2 集，顶栏 2/6 |
| 桌面 1440×900 | Feed 480px 居中，无横向滚动 |
| 控制台 | 无运行时异常、无接口错误日志 |

验收产生的临时用户、积分账户、解锁、历史已清理，测试用户对剧集播放/浏览计数的增量已回退。1003 的当前余额/解锁/评论以数据库为准（验收期间可能有人工操作，未强制回滚）。
