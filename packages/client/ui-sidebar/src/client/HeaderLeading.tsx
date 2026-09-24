/**
 * The conversation header's leading seat on a narrow frame: the way back into
 * a hidden sidebar. The frame hides the 56px rail once a session header is
 * drawn, so this seat's expand button is the only toggle left; it reads the
 * frame's derived sidebar state through the global useSidebarInfo hook and
 * renders nothing while any of narrow/collapsed/headerVisible is false, so
 * the title keeps the header's left edge. macOS desktop hosts its reopen
 * controls in the frame's shell.leading window-chrome seat instead.
 */
import type { ReactNode } from 'react'
import { IconPanelLeftOutlineRegular, isDarwinDesktop, Tooltip } from '@deepseek-ai/dsh-client-ui-primitives'
import type { InjectFace, PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
// Type-only: pulls the conversation header slot declarations.
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client'
import type { SidebarRootInjected } from './contract/slots.ts'
import css from './HeaderLeading.module.css'

/** Full props of the conversation-header leading occupant. */
export type HeaderLeadingProps =
  PropsRuntime<'conversation.header.leading'>
  & InjectFace<SidebarRootInjected>
  & PropsLocale<'sidebar'>

/**
 * The narrow-frame sidebar expand button.
 * @param props - Injected sidebar actions, the sidebar locale seat, and the frame's sidebar info.
 * @returns the expand button, or null while the rail is visible or the frame's window chrome hosts the controls.
 */
export function HeaderLeading({ useSidebarInfo, toggleSidebar, t }: HeaderLeadingProps): ReactNode {
  const info = useSidebarInfo(value => value)
  // macOS desktop hosts the reopen controls in the frame's shell.leading
  // window-chrome seat; this seat stays empty there.
  if (isDarwinDesktop()) return null
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
        <IconPanelLeftOutlineRegular />
      </button>
    </Tooltip>
  )
}
