// @vitest-environment jsdom
/**
 * The way back into a hidden sidebar: the header's leading button exists
 * exactly while the frame is narrow, the sidebar is collapsed, and the header
 * is drawn; it asks the layout service to expand, and renders nothing
 * otherwise so the title keeps the header's left edge.
 */
import { describe, expect, it } from 'vitest'
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { vi } from 'vitest'
import { createSnapshotStore } from '@deepseek-ai/dsh-client-store'
import { bindSnapshotSelector } from '@deepseek-ai/dsh-client-test-runtime'
import { SidebarExpandButton } from '../src/client/SidebarExpandButton.tsx'
import type { SidebarExpandButtonProps } from '../src/client/SidebarExpandButton.tsx'

type SidebarInfo = { narrow: boolean; collapsed: boolean; headerVisible: boolean }

/**
 * Mount the button over a real SidebarInfo store. It reads three of its props;
 * the rest of the standard kit is framework-injected and never touched here, so
 * one documented cast keeps the harness to what is actually exercised.
 */
function mountButton(info: SidebarInfo, toggleSidebar = vi.fn()) {
  const store = createSnapshotStore<SidebarInfo>(info)
  const props = {
    useSidebarInfo: bindSnapshotSelector(store),
    toggleSidebar,
    // Copy is the dictionary's contract; the key stands in for the translation.
    t: (key: string) => key,
  } as unknown as SidebarExpandButtonProps
  const view = render(<SidebarExpandButton {...props} />)
  const control = (): HTMLElement | null => view.container.querySelector('[data-sidebar-expand]')
  return { store, view, control, toggleSidebar }
}

describe('SidebarExpandButton', () => {
  it('offers the way in while the rail is hidden, and asks the sidebar to expand', () => {
    const { control, toggleSidebar } = mountButton({ narrow: true, collapsed: true, headerVisible: true })
    const button = control()
    if (button === null) throw new Error('expected the expand control')
    expect(button.getAttribute('aria-label')).toBe('toggle.open')
    fireEvent.click(button)
    expect(toggleSidebar).toHaveBeenCalledOnce()
    cleanup()
  })

  it('renders nothing while any of the three facts is false', () => {
    // Wide frame: the rail is always drawn, so there is no way back to offer.
    let { control } = mountButton({ narrow: false, collapsed: true, headerVisible: true })
    expect(control()).toBeNull()
    cleanup()
    // Expanded: the rail (or the drawer) is on screen with its own toggle.
    ;({ control } = mountButton({ narrow: true, collapsed: false, headerVisible: true }))
    expect(control()).toBeNull()
    cleanup()
    // Header hidden (the blank Hero): the rail keeps its own toggle.
    ;({ control } = mountButton({ narrow: true, collapsed: true, headerVisible: false }))
    expect(control()).toBeNull()
    cleanup()
  })

  it('comes and goes as the frame state moves', () => {
    const { store, control } = mountButton({ narrow: true, collapsed: true, headerVisible: true })
    expect(control()).not.toBeNull()
    act(() => { store.set({ narrow: true, collapsed: false, headerVisible: true }) })
    expect(control()).toBeNull()
    act(() => { store.set({ narrow: true, collapsed: true, headerVisible: false }) })
    expect(control()).toBeNull()
    act(() => { store.set({ narrow: true, collapsed: true, headerVisible: true }) })
    expect(control()).not.toBeNull()
    cleanup()
  })
})
