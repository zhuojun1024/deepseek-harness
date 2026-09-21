/**
 * The conversation header's leading seat: the way back into a hidden sidebar.
 * On macOS desktop a collapsed sidebar hides entirely (no rail), taking both
 * controls off screen; this occupant puts the sidebar-open and New Session
 * controls back beside the traffic lights. Mounted whenever the platform
 * matches; visibility rides the AppFrame-published `data-sidebar-collapsed`
 * attribute in CSS, so no collapse-state pipe is added here. On a narrow
 * frame the frame hides the 56px rail once the header is drawn, so the seat's
 * expand button is the only toggle left; it reads the frame's derived sidebar
 * state through the global useSidebarInfo hook and renders nothing while any
 * of narrow/collapsed/headerVisible is false, so the title keeps the header's
 * left edge.
 */
import type { ReactNode } from 'react'
import {
  IconNewChatOutline16, IconPanelLeftOutline16, isDarwinDesktop, Tooltip,
} from '@deepseek-ai/dsh-client-ui-primitives'
import type { InjectFace, PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
// Type-only: pulls the conversation header slot declarations.
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import type { SidebarRootInjected } from './contract/slots.ts'
import css from './HeaderLeading.module.css'

/** Full props of the conversation-header leading occupant. */
export type HeaderLeadingProps =
  PropsRuntime<'conversation.session.header.leading'>
  & InjectFace<SidebarRootInjected>
  & PropsLocale<'sidebar'>

/**
 * Sidebar-open and New Session controls on macOS desktop; the narrow-frame
 * expand button elsewhere.
 * @param props - Injected sidebar actions, the sidebar locale seat, and the frame's sidebar info.
 * @returns the header controls, or null while none applies.
 */
export function HeaderLeading({ useSidebarInfo, toggleSidebar, startSession, t }: HeaderLeadingProps): ReactNode {
  const info = useSidebarInfo(value => value)
  if (isDarwinDesktop()) {
    return (
      <div className={css.controls}>
        <Tooltip label={t('toggle.open')} delayMs={500}>
          <button
            type="button"
            className={css.iconButton}
            aria-label={t('toggle.open')}
            onClick={() => { toggleSidebar() }}
          >
            <IconPanelLeftOutline16 size={16} />
          </button>
        </Tooltip>
        <Tooltip label={t('session.new.label')} delayMs={500}>
          <button
            type="button"
            className={css.iconButton}
            aria-label={t('session.new.label')}
            onClick={() => { startSession() }}
          >
            <IconNewChatOutline16 size={16} />
          </button>
        </Tooltip>
      </div>
    )
  }
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
        <IconPanelLeftOutline16 />
      </button>
    </Tooltip>
  )
}
