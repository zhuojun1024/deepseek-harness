# Agent Note: Narrow sidebar rail and header expand button

Status: implemented

English | [中文](2026-09-14-narrow-sidebar-rail-and-header-expand.zh.md)

## Problem

On a narrow frame the left navigation sidebar collapses to a 56px control rail. Inside a session the session header is drawn, so the rail is redundant chrome; but on the hero/blank page there is no header, and the rail is the only way into the sidebar. The user asked to hide the collapsed rail while a session header is visible and to move the way back into the sidebar into that header, while keeping the rail on the header-less hero page.

## Decision

**The frame hides the collapsed rail while a session header is drawn.** The session header reports its own visibility into the layout store through the new `setHeaderVisible` action, and [AppFrame.tsx](../../../../packages/client/ui-layout/src/client/AppFrame.tsx) sizes the sidebar track to 0 while `narrow && sidebarCollapsed && headerVisible` (the `narrowHidden` condition) and omits the column. The header reports `!hideChrome` on mount and on every change, and `false` on unmount, so a global panel with no session keeps the rail. This is the rightbar's report→store→frame pattern applied to the left column: the occupant reports a fact, the store records it, the frame sizes from it.

**The header's leading seat is the way back into a hidden sidebar.** [ConversationSession.tsx](../../../../packages/client/ui-conversation/src/client/skeleton/ConversationSession.tsx) declares `conversation.session.header.leading` (single, session scope) before the breadcrumb, and [SidebarExpandButton.tsx](../../../../packages/client/ui-sidebar/src/client/SidebarExpandButton.tsx) registers into it. The button renders only while the same three facts hide the rail — `narrow && collapsed && headerVisible` — and its click calls `ctx.layout.toggleSidebar()`. It is a 28px icon button carrying the `toggle.open` locale key and a tooltip; the empty seat collapses to nothing when no occupant is present.

**`sidebarInfo` is a root-scoped derived fact on the standard props share.** [index.ts](../../../../packages/client/ui-layout/src/client/index.ts) derives `{ narrow, collapsed, headerVisible }` from the layout store and publishes it through the `provideRoot` `sidebarInfo` hook, which the `GlobalStandardProps` merge exposes as `useSidebarInfo` to every slot component. It is memoized over the store snapshot so `getSnapshot` returns the same reference until the frame or the header's visibility moves, the snapshot-stability contract the renderer's uSES bridge requires.

## Alternatives considered

**Keep the 56px rail always on narrow.** The pre-change behavior. Rejected: it is redundant chrome inside a session, and the user asked to hide it; the hero page keeps the rail, so the way in is never lost.

**Put the expand button in the rail's own column.** Rejected: the rail is exactly the column that is hidden, so a button inside it would be hidden with it; the button must live where the rail is absent — the header.

**Make `sidebarInfo` a registrant-private `hooks` compartment fact.** It has a single consumer (the header button, registered by ui-sidebar), which is the shape a per-registration hook fits. Rejected: the fact is derived from the root layout store and is root-scoped, so it belongs in the root `provideRoot`/`GlobalStandardProps` channel like `panelInfo`; a per-registration hook would force ui-layout to publish the observable across a service boundary, or to duplicate the derivation, for one consumer.

## Consequences

`useSidebarInfo` is a new member of the pre-stable `GlobalStandardProps` share; every component spec that hand-builds the standard props now supplies it (the `usePanelInfo` precedent has the identical blast radius), and the two layout-action fakes supply `setHeaderVisible`. The `narrowHidden` track-zero path is covered by the app-frame spec (collapse wide, narrow, header on → track 0; header off → 56; wide again → 56), the header's reporting by the conversation skeleton spec, the button's three-fact gate and toggle by its own spec, and the leading-seat registration by the ui-sidebar apply spec. `sidebarInfo` is memoized, so an unrelated store write does not re-render the button.
