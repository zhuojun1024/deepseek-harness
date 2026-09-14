# Agent Note: 窄屏侧边栏 rail 与顶栏展开按钮

Status: implemented

[English](2026-09-14-narrow-sidebar-rail-and-header-expand.md) | 中文

## Problem

窄屏下左侧导航侧边栏折叠成 56px 的控制 rail。进入会话后会话顶栏被绘制，rail 就成了多余的 chrome；但 hero/空白页没有顶栏，rail 是进入侧边栏的唯一入口。用户要求：会话顶栏可见时隐藏折叠的 rail，把回到侧边栏的入口放进顶栏；而 hero 页保留 rail。

## Decision

**顶栏绘制时，frame 隐藏折叠的 rail。** 会话顶栏经新的 `setHeaderVisible` action 把自己的可见性报告进布局 store，[AppFrame.tsx](../../../../packages/client/ui-layout/src/client/AppFrame.tsx) 在 `narrow && sidebarCollapsed && headerVisible`（`narrowHidden` 条件）成立时把侧边栏 track 定为 0 并省略该列。顶栏在挂载和每次变化时报告 `!hideChrome`，卸载时报告 `false`，于是没有会话的全局面板仍保留 rail。这是右栏 report→store→frame 模式在左列的应用：占用者报告事实，store 记录，frame 据此定尺寸。

**顶栏 leading 座位是回到隐藏侧边栏的入口。** [ConversationSession.tsx](../../../../packages/client/ui-conversation/src/client/skeleton/ConversationSession.tsx) 在面包屑前声明 `conversation.session.header.leading`（single，session 作用域），[SidebarExpandButton.tsx](../../../../packages/client/ui-sidebar/src/client/SidebarExpandButton.tsx) 注册进它。按钮只在同样三个事实隐藏 rail 时渲染——`narrow && collapsed && headerVisible`——点击调用 `ctx.layout.toggleSidebar()`。它是一枚 28px 图标按钮，携带 `toggle.open` 词条与 tooltip；座位为空时塌缩为无。

**`sidebarInfo` 是标准 props 分享上的根作用域派生事实。** [index.ts](../../../../packages/client/ui-layout/src/client/index.ts) 从布局 store 派生 `{ narrow, collapsed, headerVisible }`，经 `provideRoot` 的 `sidebarInfo` hook 发布，`GlobalStandardProps` 合并把它作为 `useSidebarInfo` 暴露给每个坑位组件。它对 store 快照做记忆化，`getSnapshot` 在 frame 或顶栏可见性变化前返回同一引用，满足渲染器 uSES 桥要求的快照稳定契约。

## Alternatives considered

**窄屏始终保留 56px rail。** 改动前的行为。否决：会话内它是多余的 chrome，用户要求隐藏；hero 页保留 rail，入口不会丢失。

**把展开按钮放进 rail 自己的列。** 否决：rail 正是要被隐藏的列，放在里面的按钮会随它一起消失；按钮必须住在 rail 缺席的地方——顶栏。

**把 `sidebarInfo` 做成注册者私有的 `hooks` 舱室事实。** 它只有一个消费者（顶栏按钮，由 ui-sidebar 注册），这正是按注册 hook 的适用形状。否决：该事实派生自根布局 store、是根作用域的，属于根 `provideRoot`/`GlobalStandardProps` 通道，与 `panelInfo` 同类；按注册 hook 会迫使 ui-layout 跨服务边界发布 observable 或为单一消费者重复派生。

## Consequences

`useSidebarInfo` 是 pre-stable `GlobalStandardProps` 分享的新成员；每个手工构建标准 props 的组件用例现在都要提供它（`usePanelInfo` 先例有完全相同的波及面），两处布局 action 假对象补充 `setHeaderVisible`。app-frame 用例覆盖 `narrowHidden` 的 track-0 路径（宽屏折叠、窄屏、顶栏开 → track 0；顶栏关 → 56；再宽屏 → 56），会话骨架用例覆盖顶栏的报告，按钮自身用例覆盖三事实门控与切换，ui-sidebar apply 用例覆盖 leading 座位注册。`sidebarInfo` 经过记忆化，无关的 store 写入不会重渲染按钮。
