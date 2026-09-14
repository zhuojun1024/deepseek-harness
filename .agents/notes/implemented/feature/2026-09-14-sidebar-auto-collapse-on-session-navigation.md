# Agent Note: Sidebar auto-collapse on session navigation (narrow frames only)

Status: implemented

English | [中文](2026-09-14-sidebar-auto-collapse-on-session-navigation.zh.md)

## Problem

The user asked for a small interaction: clicking "New Session" or switching to another Session in the sidebar should automatically close the sidebar so the conversation owns the full width the moment a navigation lands. On follow-up the scope was narrowed: this auto-collapse applies to H5 (narrow) frames only; a PC browser (wide frame) keeps its original logic, where a navigation does not touch the sidebar. The two gestures already funnel through the workspace service — a row click calls openSession, and New Session calls startSession (which either clears to the blank hero or opens a workspace and then a session) — so a single close call at the right seams covers both, and the store decides whether the frame is narrow.

## Decision

**A one-way collapseSidebar layout action collapses the sidebar for a navigation, narrow frames only.** [stores.ts](../../../../packages/client/ui-layout/src/client/stores.ts) adds collapseSidebar beside toggleSidebar. It is a no-op on a wide frame (viewportWidth >= SIDEBAR_AUTO_COLLAPSE), which is the PC behavior: the sidebar keeps its pre-navigation state. On a narrow frame it drops the narrowExpanded override (the width preference survives, so a later re-widen restores the pre-squeeze layout). It is idempotent — immer returns the base state when the override is already dropped, so a repeated call keeps the snapshot reference and re-renders nothing. The action is exposed on the ILayout face and forwarded by LayoutController in [service.ts](../../../../packages/client/ui-layout/src/client/service.ts); the caller never checks the frame width, the store owns that decision.

**The close is wired at the user-gesture seams, not the startup reconcile.** [navigation.ts](../../../../packages/client/ui-workspace/src/client/navigation.ts) calls ctx.layout.collapseSidebar() in openSession (covering a row click, the workspace-backed New Session, and a fork) and in startSession's no-workspace branch (the blank hero). The startup auto-reconcile opens through sessions.open directly and never reaches openSession, so an initial selection does not collapse a sidebar the user left open. On a narrow frame the hero branch keeps the 56px rail as the way back (the hero has no session header to host an expand control); the active-session branch hides the rail and the header's expand button is the way back — the two facts the narrow-rail note already establishes. On a wide frame the call is a no-op, so the PC keeps its original sidebar state through a navigation.

## Alternatives considered

**Collapse on every frame width.** The first cut. Rejected on follow-up: the user wants the PC (wide) to keep its original logic, so the collapse is gated to narrow frames in the store.

**Reopen the sidebar after a navigation instead of closing it.** Rejected: the user asked for the opposite — the conversation should own the width when a navigation lands (on H5).

**Close via setSidebar(0).** Rejected: setSidebar clamps to [SIDEBAR_MIN, SIDEBAR_MAX] (264–420), so 0 is rounded back up; a dedicated one-way action is required to reach the collapsed state.

**Collapse in the sidebar's own click handlers.** Rejected: the gestures already converge on the workspace service, and closing there keeps the rule in one place and applies to every entry point (row, New Session, fork) without touching the presentation layer.

## Consequences

collapseSidebar is a new member of the pre-stable ILayout face; the layout-action fakes in the layout service spec, the workspaces-service bench, and the ui-workspace apply spec now supply it. The layout-store spec pins the wide no-op (a wide frame keeps its drag width and the narrow override untouched), the narrow collapse (override dropped, width kept), and the idempotent no-op. The workspaces-service spec asserts the close fires on both openSession and the startSession clear path. The narrow-rail note's three-fact gate is unchanged: the new action only writes the collapsed fact on a narrow frame, and the existing header/rail machinery decides what to draw from it.
