# DSH Android

自用的 Android 16 WebView 壳，用来连接已经暴露到公网的 DSH Web。

它**不内置前端**：页面、`window.__DSH_BOOT__`、客户端插件 bundle 都由 `dsh web` 服务端提供。APK 只负责登录、保存凭据、承载 WebView，以及处理 Android 16 的 edge-to-edge 安全区。

## 已实现（MVP）

- 首屏手动输入服务器地址，默认为空，可填写自己的 HTTPS 域名；
- 手动输入 token，支持直接粘贴 `dsh web` 启动时打印的整条 URL；
- 先用 `GET /?token=...` 预检：303 视为 token 有效，401/403/404/5xx/代理登录页分别提示；
- 预检通过后 WebView 加载 `/?token=...`，由服务端 303 设置 HttpOnly Cookie；有效期由 `cookieMaxAgeDays` 决定（默认 30 天）；
- 只有 WebView 真正跳转到干净地址后才保存，避免保存坏 token；
- 下次启动优先使用 WebView Cookie 直接进主页；Cookie 失效时才用保存的 token 重新换取；
- token 用 AndroidKeyStore AES/GCM 加密后存入应用私有 SharedPreferences；
- Android 16 edge-to-edge：
  - 顶部 padding = `systemBars ∪ displayCutout`；
  - 底部 padding = `max(systemBars.bottom, ime.bottom)`；
  - 状态栏、手势提示线、导航栏、IME 都不会遮挡页面或输入框；
  - 键盘弹出时自动把焦点输入框滚入可视区；
- 返回手势：优先 WebView 历史，其次退出；33+ 使用 `OnBackInvokedCallback`；
- HTTPS-only、禁止 cleartext、禁止 mixed content、TLS 错误一律取消、禁止 file/content 访问；
- 文件选择、下载（带 Cookie）、外部链接交给系统浏览器；
- 登录后启动前台服务，通过独立的 API operator 订阅 `$events` 的 `api-session/status`；一轮 Agent 从 running 变为 idle（会话结束）且 App 不在前台时发通知；
- 通知点击回到 App；前台服务常驻通知带“停止”；Android 13+ 启动时请求通知权限。

## 变更

- 0.2.0：新增后台会话完成通知（独立 API operator + `$events`，仅 App 不在前台时提醒）；新增通知权限、前台服务、通知渠道和通知小图标；`session/list` 预读当前 running 会话，避免监听启动时已在跑的任务漏提醒；token 失效时发“登录已失效”通知，点击直达登录页；
- 0.1.1：修复首次登录时地址栏裸域名被误当成 token 的问题；图标换成白色背景 + 黑色虎鲸的自适应图标。

## 不包含

- 超级岛/焦点通知的原生适配：当前发的是标准通知；超级岛需要小米的岛通知权限（见下文），不是标准 Android 通知 API 能直接拿到的；
- 后台审批：服务收到 approval/user-question waterfall 只读不答，审批仍由前台 WebView 处理；后台遇到审批时任务会停在那里，不会“完成”；
- 扫码登录、设备配对、按设备撤销；
- 相机/麦克风权限（语音输入会被拒绝）；
- 退出登录按钮；需要重新登录时清除应用数据即可。

## 构建

要求：

- JDK 17；
- Android SDK Platform 36、Build-Tools 36.0.0；
- Gradle 8.13（仓库已带 wrapper；wrapper 的下载地址指向腾讯云镜像）。

`local.properties` 里的 `sdk.dir` 按本机 SDK 路径修改，然后：

```powershell
cd android
.\gradlew.bat assembleDebug assembleRelease
```

release 签名需要 `keystore.properties` 和 `keystore/dsh-release.jks`；缺失时 release 包不签名。

产物：

```
android/app/build/outputs/apk/debug/app-debug.apk
android/app/build/outputs/apk/release/app-release.apk
```

release 包已经用本机生成的密钥签名，包名为 `com.zhuojun1024.dsh`，可以直接安装：

```powershell
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

debug 包的 applicationId 带 `.debug` 后缀（`com.zhuojun1024.dsh.debug`），用于调试，可以和 release 包共存。

0.2.0 可以直接覆盖安装 0.1.1（或 0.1.0），应用数据（已保存的 token 和 WebView Cookie）会保留；不要先卸载，否则需要重新登录。0.2.0 首次启动会多一个通知权限弹窗。

签名材料：

- `android/keystore/dsh-release.jks`：release 签名密钥；
- `android/keystore.properties`：密钥口令和别名。

这两个文件已在 `android/.gitignore` 中忽略，**请自行备份**。以后升级 release APK 必须用同一把密钥签名，否则需要先卸载旧版本。如果换一台机器构建，把这两个文件一起带过去即可。

## 登录流程

1. 输入地址（可省略 `https://`）和 token；
2. App 请求 `https://<host>/?token=<token>`，不跟随重定向：
   - `303` 且 `Location` 指向站点根路径/`index.html`：通过；
   - `401`：token 无效或过期；
   - `403`：`--trusted-host` 未包含该域名；
   - `404`：路径前缀不对；
   - `200`：服务端没有走 token 登录（即使返回 DSH 页面也会提示），或返回的是 NAS/代理登录页；
3. WebView 加载同一个 URL，服务端 303 设置 Cookie 并跳到干净地址；
4. App 确认最终 URL 同源、且不再包含 `token=`，保存 host + token；
5. 再次启动时：
   - WebView Cookie 还在：直接加载 `https://<host>/`；
   - Cookie 失效但 token 还有效：自动用 token 重新换取；
   - token 也失效：回到登录页，粘贴新 token；
6. 进入主页后启动后台监听服务（见下文）。

## 后台完成通知（0.2.0）

登录成功/下次直接进主页后，App 启动一个 `specialUse` 前台服务。服务不依赖 WebView 的 Cookie 或 JavaScript：

1. 用保存的进程 token 请求 `GET /connect?token=...&interactions=answer`，得到一个**独立 API operator** Cookie；
   - 不能使用 WebView 的浏览器 Cookie：它属于 WebView peer，同一 peer 再开 WebSocket 会被 409 拒绝；
   - 也不能用 `interactions=none`：DSH 禁止 passive peer 订阅 `$events`，必须声明 `answer`。服务只读 `$events` 的状态帧，不回答 approval/user-question waterfall，审批仍由前台 WebView 回答；
2. 打开 `wss://<host>/api/remote.mux`，发送 `$events` 的 `open`；
3. 在打开事件流前先调用一次 `session/list`（`payload.args._request = {}`），把当前 `running: true` 的会话放进内存集合，避免“服务刚启动时任务已经在跑”的场景漏提醒；
4. 监听 `api-session/status` 事件，参数是 `(sessionId, running)`：
   - `running: true` 记录；
   - `running: false` 且之前在集合里：移除；只有当 App 没有窗口焦点时发通知；
5. App 通过 `onWindowFocusChanged`/`onPause`/`onResume` 维护前台标志；回到前台后完成的轮次不会补发通知；
6. 服务断线按 2s→60s 退避重连；每次重连重新换取 operator Cookie（peer 随 WebSocket 关闭而释放）；token 失效（401/403）时停止服务，并发一条“登录已失效”通知；点击通知打开登录页（地址保留、token 清空），重新登录后会再次启动。

### 权限与系统限制

- Android 13+ 需要通知权限；首次启动会请求，拒绝后不显示通知（前台服务仍可能运行，但通知栏里看不到完成提醒）；
- 前台服务需要 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`FOREGROUND_SERVICE_SPECIAL_USE` 权限；Manifest 中声明 `dataSync|specialUse`，Android 14+ 运行时使用 `specialUse`；
- token 失效时会发一条独立的“登录已失效”通知（高优先级渠道“登录提醒”），点击后会直接打开登录页；如果通知权限被拒绝，则只能手动清数据/重新登录；
- 小米澎湃 OS 需要允许 DSH 的通知，并把省电策略设为“无限制”/允许自启动（不同版本路径不同）；否则系统可能在锁屏/后台一段时间后杀掉服务；
- 前台服务常驻通知是系统要求，不代表“前台有会话完成时也通知”；完成通知只在 App 无焦点时发；
- 前台服务通知的“停止”会停止监听；下次打开 App 会重新启动。想临时不提醒可以点它，或关闭通知权限。

### 超级岛

当前实现是**标准 Android 通知**，不保证出现在小米超级岛。小米的“焦点通知/岛通知”走的是另一套协议：在本地通知上添加 `miui.focus.param`（以及 `miui.focus.pics` 等）扩展字段，并且需要向小米申请**岛通知权限/白名单**（官方文档里有邮件申请模板和 MiPush 接入方式）。个人侧载应用需要按文档申请并通过审核，能否拿到不保证，所以本次没有把 `miui.focus.*` 硬塞进通知里。

官方入口：

- 焦点通知/岛通知客户端与 MiPush 接入：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131>
- 权限与模板说明：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132>

如果只想先把通知稳定跑通，当前版本即可；要上超级岛，可选：

1. 用已注册的 MiPush appid 向小米申请岛通知权限，再在完成通知上补 `miui.focus.param`；
2. 用 LSPosed/Xposed 模块把标准通知注入超级岛（例如 `HyperIslandCustom`/`HyperIsland` 这类社区模块），但这是系统版本相关的额外模块，不属于本 APK；
3. 如果 HyperOS 3.1 使用 Android 16 Live Updates 的标准 API，可能能用标准 Live Update，但需要在目标机上验证，当前没有实现。

## 关于 token 和 Cookie

`dsh web` 启动时打印在 URL 里的是**进程 token**（launch token），服务器重启/重新部署后会变化。它和 WebView 的登录 Cookie 是两回事：

- **launch token**：进程级随机值，没有单独的有效期；进程重启后服务端会生成新值，旧值立即失效。它只用于 `/` 和 `/connect` 的交换；
- **浏览器 Cookie**：WebView 用 launch token 交换到的登录 Cookie，有效期由 `cookieMaxAgeDays` 决定（默认 30 天）。Cookie 用 `$DSH_HOME/.credentials.yaml` 里持久化的签名密钥签发，所以服务器重启后浏览器 Cookie 仍然有效，主页通常还能直接进；
- **API operator Cookie**：0.2.0 的后台监听用 launch token 调 `/connect` 换到，也用同一个 Cookie 有效期；但它的 payload 里带 operator 的 peer id。服务器重启后这个 peer 已经不存在，所以旧 API Cookie 即使未到有效期也会 401，必须用新 launch token 重新换；
- **如果服务器配置了 `permanentToken`**：App 的 token 直接填这个永久 token。它和进程 token 并行生效，服务器重启后后台监听仍能用同一个值重新换 operator，不需要重新登录；这需要服务端 DSH 版本包含永久 token 支持；
- **所以**：重启后“主页能进”不等于“后台提醒还能用”。主页靠浏览器 Cookie；后台提醒靠新的 launch token（或配置的永久 token）。0.2.0 在后台监听失效时会发“登录已失效”通知，点它重新登录即可；
- Cookie 的过期时间是**签发时**写死的：把 `cookieMaxAgeDays` 改大不会延长已经签发的 Cookie；改小会让签发时有效期超过新上限的旧 Cookie 被拒绝（源码会检查 `expiresAt - issuedAt`），需要重新登录；
- 手机丢失：没有远程撤销；请清理应用数据或改服务端配置。

## Android 16 适配说明

- `targetSdk 36`、`minSdk 30`；
- `Window.setDecorFitsSystemWindows(false)` 后，root 自己消费 insets；
- `windowSoftInputMode="adjustNothing"`，键盘高度完全由 `WindowInsets.Type.ime()` 处理，避免 `adjustResize` 与手动 padding 双重缩放；
- 状态栏/导航栏透明；窗口背景（`windowBackground`）与 DSH 页面基础背景一致（浅色 `#FFFFFF`，深色 `#151517`），root 不重复绘制背景，避免 overdraw；
- WebView 不再加 `fitsSystemWindows`，避免二次 padding；
- 横向时同时处理左右 cutout inset。

## 构建验证

- release：`com.zhuojun1024.dsh` 0.2.0（versionCode 3），minSdk 30 / targetSdk 36，APK Signature Scheme v2，`zipalign -c 4` 通过；
- 权限：`INTERNET`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`FOREGROUND_SERVICE_SPECIAL_USE`；
- `lintRelease`：0 errors，6 warnings（备份属性、布局合并、按钮样式、OkHttp 有新版本等提示，不影响构建）；
- 用本地 DSH web 实例验证了：
  - `GET /connect?token=...&interactions=answer` 返回 302 和 operator Cookie；
  - 带 Cookie + Origin 打开 `/api/remote.mux` 并发送 `$events`，收到 `ready`；
  - 在同一 WebSocket 打开期间用 operator Cookie 调用 `POST /api/session/list`，`payload.args._request = {}` 返回 200；修正前 `args: {}` 会报 `missing "_request"`；
  - 未触发真实 running→idle 轮次（临时实例没有跑模型），状态事件解析按源码字段实现；
- 线上实例的 `https://<host>/` 与 `/?token=invalid` 在 0.1.1 时返回 401；0.2.0 复用同一登录链路；
- 构建环境未连接真机/模拟器；0.2.0 的通知权限、前台服务、HyperOS 后台存活、实际完成提醒需要在手机上确认。

## 已知限制

- 页面内切换 DSH 主题（不跟随系统）时，原生状态栏图标颜色不会跟着变；
- 页面内的 blob 下载无法保存，会提示用桌面端；
- 后台服务依赖小米的后台策略；被系统杀掉后，在下一次打开 App 前不会提醒；
- 通知是标准通知，不保证进超级岛；
- 后台遇到审批/用户提问时，服务不会代替用户回答，任务会停在等待状态，因此不会触发“完成”通知；
- `api-session/status` 只表示 Agent 从 running 变 idle，abort/error 也会触发，因此通知里的“已完成”在失败时也按“本轮结束”理解；
- 监听启动前/重连窗口内已经结束的轮次可能漏掉；服务启动时会用 `session/list` 补一次当前 running 状态，但不能覆盖所有断线窗口；
- 通知正文只有 sessionId 后 8 位，没有会话标题；
- 没有自签名证书例外；证书必须是系统信任的 CA 签发；
- 没有自动更新。

## 目录

```
android/
  app/src/main/java/com/zhuojun1024/dsh/
    MainActivity.java          Activity、WebView、登录流程、insets、前台状态
    SessionMonitorService.java 前台服务、API operator、$events、完成通知
    AppVisibility.java         进程内前台/焦点标志
    TokenStore.java            AndroidKeyStore + SharedPreferences
    UrlTools.java              地址/token 规范化、同源判断、/connect URL
  app/src/main/res/            布局、主题、颜色、图标、通知小图标
```

超级岛属于系统级能力，不建议继续往这个壳里塞；标准通知已经由独立前台服务完成。如果后面拿到小米权限或决定走 LSPosed 模块，再单独做。
