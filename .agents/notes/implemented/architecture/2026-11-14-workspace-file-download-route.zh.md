# Agent Note：通过仅 owner 可达的附件路由下载工作区文件

Status: implemented

[English](2026-11-14-workspace-file-download-route.md) | 中文

## 问题

文件菜单缺少把工作区文件保存到显示 GUI 的设备上的入口。现有两条通道都承载不了这个手势：

| 通道 | 行为 | 为何不够 |
|---|---|---|
| `readBytes` Remote | 经 Gateway 的 multipart 响应返回 `Uint8Array` | 它是数据通道：每次保存都先把整个文件读进浏览器内存 |
| `BrowserFileActions.save` | `fetch` 源地址、构造 `Blob`、在 `blob:` 对象 URL 上点击 `a[download]` | URL scheme 是 `blob:`。壳的 WebView 下载监听器拒绝 `blob:` 与 `data:` URL；浏览器也只能按 anchor 提示命名，结果不是下载，而是一份页面内存副本 |

还有一处未被使用的面：`/api/file` 以文档形式提供文件字节，但不带 `Content-Disposition`。把下载指向它，WebView 仍会渲染而不是保存。

## 决策

新增一条 Host 路由与一个菜单动作。

| 项 | 约定 |
|---|---|
| 注册 | 在 Connection Fetch 载体上注册 `GET`/`HEAD /api/workspace-file/download`，精确路径、请求体 buffered；由 `WorkspaceFiles` 通过 `ctx.inject(['connection'], …)` 注册，因此没有 Web 载体的 profile 仍能正常使用本服务 |
| 鉴权 | 载体的 `admit` 先执行：Host/Origin 围栏、签名浏览器 cookie，以及精确 Fetch 路由既有的仅 owner 检查。非 owner API 凭证在进入处理器前收到 403 |
| 身份 | `sessionId` 指定用于解析路径的、由 header 推导工作区根的 Session；缺失或未知分别是 400 或 404 |
| 读取权限 | 与 `readBytes` 相同的关：先 `lstat`、仅普通文件、组合文件系统的读取权限（工作区外路径可读），以及 `maxFileBytes` 上限 |
| 响应 | 200 携带字节、`Content-Disposition: attachment`、`Content-Type: application/octet-stream`、`Content-Length`、`Cache-Control: private, no-store`、`X-Content-Type-Options: nosniff`；HEAD 请求返回同样的头但不带正文 |
| 文件名 | 路径末段；非可打印 ASCII 的名字额外携带 RFC 5987 `filename*` 形式，并附 ASCII 回退名 |
| 拒绝 | 查询缺失或非法为 400，未知 Session 或文件缺失为 404，非常规文件或权限拒绝为 403，超过 `maxFileBytes` 为 413，取消为 499；正文只给出失败码，绝不带主机路径 |
| Client | `file` 菜单的下载动作先用 `HEAD` 探测路由，再在路由 URL 上点击 `a[download]`，浏览器与壳下载监听器都能保存。图片菜单保留 `BrowserFileActions` 路径，其来源通常是 URL 而非工作区路径 |

### 实现

| 包 | 落实 |
|---|---|
| `api/workspace-files` | `src/routes.ts` 拥有绝对路径；`WorkspaceFiles.serveDownload` 应答该路由；`sessionScope` 就是所有读取已在使用的、由 header 推导的作用域，现在由 lookup 注册与该路由共享；`WorkspaceFilesConnection` 接口让载体的浏览器侧类型不进入这个 Host 工程 |
| `util/workspace-path` | `workspaceFileDownloadUrl(base, sessionId, path)` 在 `fileMediaUrl` 旁构造文档相对 URL |
| `client/ui-file-actions` | `startAttachmentDownload(url, filename)` 点击 anchor；`file` 菜单的下载项先探测再导航 |

## 已考虑的替代方案

- **让壳接受 `blob:`/`data:` URL。** 需要新增 JavaScript 桥并把文件以 base64 过桥，30 MiB 的文件要付两倍内存，而且只修好壳；普通手机浏览器仍只会得到自己那份 `blob:` 副本。
- **让壳自己抓取 `/api/file` URL。** 仍是仅壳改动，仍没有 `Content-Disposition`，且重复了下载监听器已有的 cookie 处理。
- **给 `/api/file` 加 `Content-Disposition`。** 该路由服务内联文档预览；附件头会让每次预览都变成下载而不是渲染。
- **流式传输而不是整文件读取。** `ctx.fs.readBytes` 是本服务拥有的唯一有界读取，`maxFileBytes` 已经给出上限；流式应答需要为一条路由新增文件系统 seam 操作。
- **把路由放在 Gateway Remote 上。** Remote 应答是给 JavaScript 的数据而非浏览器下载，同样要付内存代价。

## 后果

- 下载手势不需要任何 Android 壳改动：壳的下载监听器本就接收带 WebView 会话 cookie 的 `http(s)` URL，系统下载管理器按 `Content-Disposition` 命名文件。
- 超过 `maxFileBytes`（默认 32 MiB）的文件无法下载；菜单动作报告拒绝，而不是保存一个被截断的文件。
- 路由在应答前把整个文件读入 Host 内存一次，因此把 `maxFileBytes` 配得很大的部署要接受这份每请求成本。
- 下载与 GUI 其余部分用同一个浏览器会话授权，所以任何能打开 GUI 的凭证都能下载 GUI 本就预览的内容；路由没有新增权限。
- 路由绕过 Gateway，因此其路径属于载体约定：`workspaceFileDownloadUrl` 与 `WORKSPACE_FILE_DOWNLOAD_PATH` 必须保持同一拼写，路由测试对此断言。
