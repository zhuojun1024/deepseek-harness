# Agent Note: 会话导航时侧边栏自动收起（仅窄屏）

Status: implemented

[English](2026-09-14-sidebar-auto-collapse-on-session-navigation.md) | 中文

## Problem

用户要求一个小交互：在侧边栏点击"新会话"或切换到另一个会话时，自动收起侧边栏，让会话一落地就独占整个宽度。后续澄清了范围：这个自动收起只针对 H5（窄屏）；PC 浏览器（宽屏）保持原有逻辑，导航不动侧边栏。两个手势本就汇聚到 workspace 服务——点击行调用 openSession，新会话调用 startSession（要么清空到空白 hero，要么打开工作区再打开会话）——所以在正确的接缝上放一处收起调用即可覆盖两者，由 store 判断当前是否窄屏。

## Decision

**一个单向的 collapseSidebar 布局 action 为导航收起侧边栏，仅窄屏生效。** [stores.ts](../../../../packages/client/ui-layout/src/client/stores.ts) 在 toggleSidebar 旁新增 collapseSidebar。它在宽屏（viewportWidth >= SIDEBAR_AUTO_COLLAPSE）是空操作，即 PC 行为：侧边栏保持导航前的状态。窄屏时丢弃 narrowExpanded 覆盖（宽度偏好保留，之后再展开能恢复挤压前的布局）。它是幂等的——覆盖已丢弃时 immer 返回基态，重复调用保持快照引用、不重渲染。该 action 暴露在 ILayout 面上，并由 [service.ts](../../../../packages/client/ui-layout/src/client/service.ts) 的 LayoutController 转发；调用方从不判断帧宽，这个决定归 store 所有。

**收起接在用户手势的接缝上，而非启动时的对账。** [navigation.ts](../../../../packages/client/ui-workspace/src/client/navigation.ts) 在 openSession（覆盖点击行、走工作区的新会话、fork）和 startSession 的无工作区分支（空白 hero）调用 ctx.layout.collapseSidebar()。启动自动对账经 sessions.open 直接打开、从不经过 openSession，所以初始选中不会收起用户留下的侧边栏。窄屏下 hero 分支保留 56px rail 作为返回入口（hero 没有会话顶栏可承载展开控件）；活动会话分支隐藏 rail、由顶栏的展开按钮作为返回入口——这正是窄屏 rail 笔记已确立的两个事实。宽屏下该调用是空操作，所以 PC 在导航中保持原有侧边栏状态。

## Alternatives considered

**每个帧宽都收起。** 第一版实现。后续否决：用户要求 PC（宽屏）保持原有逻辑，所以收起在 store 里被限定到窄屏。

**导航后重新展开侧边栏而非收起。** 否决：用户要求相反——导航落地时会话应独占宽度（在 H5 上）。

**经 setSidebar(0) 收起。** 否决：setSidebar 把宽度夹在 [SIDEBAR_MIN, SIDEBAR_MAX]（264–420），0 会被圆回；要到达收起态需要一个专门的单向 action。

**在侧边栏自己的点击处理器里收起。** 否决：手势已汇聚到 workspace 服务，在那里收起把规则集中在一处，并适用于每个入口（行、新会话、fork），而不动展示层。

## Consequences

collapseSidebar 是 pre-stable ILayout 面的新成员；布局 service 用例、workspaces-service bench、ui-workspace apply 用例中的布局 action 假对象现在都要提供它。布局 store 用例钉住宽屏空操作（宽屏保持拖拽宽度、窄屏覆盖不动）、窄屏收起（丢覆盖、保宽度）与幂等空操作。workspaces-service 用例断言 openSession 与 startSession 清空分支都触发收起。窄屏 rail 笔记的三事实门控不变：新 action 只在窄屏写收起事实，由既有的顶栏/rail 机制据此决定绘制什么。
