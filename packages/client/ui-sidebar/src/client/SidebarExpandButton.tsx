/**
 * The way back into a hidden sidebar: one button in the conversation header's
 * leading seat, shown only while the frame is narrow, the sidebar is collapsed,
 * and the header is drawn.
 *
 * It lives in the conversation's own header rather than in the frame's left
 * column so that a collapsed sidebar on a narrow frame costs the conversation
 * nothing — no rail, no width, and the transcript's scrollbar stays at the
 * column's edge. The frame hides the 56px rail in exactly this state (the
 * rail's own toggle is gone, so this button is the only way back in); on the
 * blank Hero the header is hidden and the rail keeps its own toggle instead.
 * While any of the three facts is false this renders nothing, and the leading
 * seat collapses with it so the title keeps the header's left edge.
 *
 * The glyph is the sidebar's own collapse icon, un-mirrored: the panel opens
 * to the left, the same affordance as the rail's toggle.
 */
import type { ReactNode } from 'react'
import { IconPanelLeftOutline16, Tooltip } from '@deepseek-ai/dsh-client-ui-primitives'
import type { InjectFace, PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-layout/client'
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import css from './SidebarExpandButton.module.css'

/** Injected share: the sidebar toggle through the layout service. */
export interface SidebarExpandButtonInjected {
  /** Toggle the sidebar column through the layout service. */
  toggleSidebar: () => void
}

/** The button's props: the header leading seat, the toggle callback, and copy. */
export type SidebarExpandButtonProps =
  & PropsRuntime<'conversation.session.header.leading'>
  & InjectFace<SidebarExpandButtonInjected>
  & PropsLocale<'sidebar'>

/** The expand control while the rail is hidden; nothing otherwise. */
export function SidebarExpandButton({ useSidebarInfo, toggleSidebar, t }: SidebarExpandButtonProps): ReactNode {
  const info = useSidebarInfo(value => value)
  if (!(info.narrow && info.collapsed && info.headerVisible)) return null
  return (
    <Tooltip label={t('toggle.open')} side="bottom" delayMs={500}>
      <button
        type="button"
        className={css.button}
        aria-label={t('toggle.open')}
        data-sidebar-expand
        onClick={() => { toggleSidebar() }}
      >
        <IconPanelLeftOutline16 className={css.icon} />
      </button>
    </Tooltip>
  )
}
