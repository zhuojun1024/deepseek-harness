import { describe, expect, it } from 'vitest'
import { workspaceFileDownloadUrl } from '../src/index.ts'

describe('workspaceFileDownloadUrl', () => {
  it.each([
    ['src/测试 文件#100%.txt', 'src/测试 文件#100%.txt'],
    ['C:\\work\\测试 文件.txt', 'C:\\work\\测试 文件.txt'],
  ])('keeps the workspace path %s intact under a mounted application', (path, encoded) => {
    const url = new URL(workspaceFileDownloadUrl('https://host/tools/dsh/', 's-1', path)!)
    expect(url.pathname).toBe('/tools/dsh/api/workspace-file/download')
    expect(url.searchParams.get('sessionId')).toBe('s-1')
    expect(url.searchParams.get('path')).toBe(encoded)
  })
  it('rejects a missing Session or an invalid path', () => {
    expect(workspaceFileDownloadUrl('https://host/', '', 'image.png')).toBeUndefined()
    expect(workspaceFileDownloadUrl('https://host/', 's-1', '')).toBeUndefined()
    expect(workspaceFileDownloadUrl('https://host/', 's-1', '//host/image.png')).toBeUndefined()
    expect(workspaceFileDownloadUrl('https://host/', 's-1', '\\\\host\\image.png')).toBeUndefined()
    expect(workspaceFileDownloadUrl('https://host/', 's-1', 'a/\u0000.png')).toBeUndefined()
    expect(workspaceFileDownloadUrl('https://host/', 's-1', 'a\n.png')).toBeUndefined()
  })
  it('serves workspace paths through the Desktop application', () => {
    for (const base of ['dsh-app://app/', 'dsh-app://app/index.html']) {
      const url = new URL(workspaceFileDownloadUrl(base, 's-1', 'image.png')!)
      expect(url.href.split('?')[0]).toBe('dsh-app://app/api/workspace-file/download')
      expect(url.searchParams.get('sessionId')).toBe('s-1')
      expect(url.searchParams.get('path')).toBe('image.png')
    }
  })
  it.each(['file:///app/', 'dsh-app://shell/', 'dsh-app://app.example/', 'dsh-app://app:80/',
    'dsh-app://user@app/', 'dsh-app://app@other/', 'about:blank'])(
    'rejects an unsupported application base %s', (base) => {
      expect(workspaceFileDownloadUrl(base, 's-1', 'image.png')).toBeUndefined()
    },
  )
})
