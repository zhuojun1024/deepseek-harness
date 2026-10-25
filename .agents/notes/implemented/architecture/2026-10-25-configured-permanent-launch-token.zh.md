# Agent Note: 配置永久启动令牌

Status: implemented

[English](2026-10-25-configured-permanent-launch-token.md) | 中文

## 问题

进程级启动令牌认证根路径 index 交换与 `/connect`，并会在进程重启时轮换。签名浏览器 cookie 能跨重启继续有效，但使用 `/connect` 获取 operator 的非浏览器 API 客户端必须在每次重启后读取新打印的令牌并重新认证用户。本工作区的 Android 后台监听就是这种客户端：DSH 重启后，即使 WebView cookie 仍有效，它的事件流也会停止，因为 API operator peer 是进程级的，旧启动令牌不再匹配。

## 决策

`dsh-client-connection` 在两个既有启动令牌交换点接受可选的 `permanentToken` Connection 配置：根路径 `GET /?token=...` 交换与 `GET /connect?token=...`。配置的令牌与进程令牌并行接受；打印的启动 URL 仍只包含进程令牌。未设置 `permanentToken` 时保持仅进程令牌的行为。

Connection 在激活时读取配置令牌并保存在内存中。修改或删除它会在 Connection 重新加载或进程重启后生效。DSH 不持久化该令牌，不打印它，也不把它写入 `authenticatedUrl`、cookie 或诊断信息。配置 schema 要求至少 32 个可打印 ASCII 字符并拒绝空白。两个启动令牌都使用既有的恒时比较辅助函数。Host/Origin 信任校验仍位于两个交换点之前；本变更不增加 Authorization header 路径、endpoint 或 cookie 格式。交换仍按配置的 `cookieMaxAgeDays` 签发 cookie，因此永久启动令牌不会延长浏览器 cookie 的绝对有效期。

该令牌是具备 owner authority 的 bearer。部署必须像保护 cookie 签名密钥一样保护它：存入密钥配置、通过 TLS 提供监听器，并通过修改配置并重新加载 Connection 来轮换。它不会绕过 `trustedHosts`、Fetch Metadata 或 TLS 策略。

配置的永久令牌部分取代[浏览器启动令牌认证](2026-08-24-browser-token-authentication.zh.md)中“持久化启动令牌或把它作为 API bearer 接受”的替代方案。旧说明仍是默认进程令牌与 cookie 模型的有效权威。

## 验证

单元覆盖证明根路径交换与 `/connect` 接受配置令牌、进程令牌仍被接受、无关令牌收到 401 且不发布 operator，以及 `authenticatedUrl` 仍使用进程令牌。集成覆盖在同一个凭据提供方上挂载两个 Connection，证明进程令牌变化后配置令牌仍能在重启后完成 `/connect` 交换，并证明旧进程令牌收到 401。配置目录由新字段与 JSDoc 生成。本地运行聚焦的 connection 测试、包类型检查、lint 与文档门禁。

## 曾考虑的替代方案

**客户端轮询浏览器 cookie 的 `/api/session/list`。** 这不需要新凭据并能跨重启，但失去事件驱动投递，且可能漏掉两次轮询之间结束的一轮。对于未配置永久令牌的部署，它仍是后备方案。

**自动持久化随机启动令牌。** 这省去配置步骤，但会静默创建没有显式轮换面的长期 owner 凭据。配置值让安全取舍可见，并给操作者明确的撤销动作。

**增加服务端签发的长期 API token endpoint。** 这会增加第二套凭据协议，并要求 peer 与 cookie 生命周期变更。配置启动令牌复用既有两个交换与 operator 签发，不增加新的 wire 约定。

**每次重启后继续重新输入进程令牌。** 这保持当前安全默认值，但阻止后台客户端无人值守重连，而后者正是报告的需求。

## 后果

泄露的永久令牌在该监听器服务的每个可信 authority 上授予完整 owner 访问，直到操作者修改或删除配置并重新加载 Connection。该令牌没有按客户端区分的身份、过期或审计；它是一份共享 owner 凭据，和打印的进程令牌、签名 cookie 一样。默认部署仍仅使用进程令牌，因此未选择启用的部署保持既有行为与风险。操作者从自己的密钥配置获取令牌，而不是从 DSH 输出获取。
