import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Context } from '@deepseek-ai/cordis'
import { LocalFileSystem } from '@deepseek-ai/dsh-fs-local'
import SessionStore, { SessionId } from '@deepseek-ai/dsh-session'
import TypertRegistry from '@deepseek-ai/dsh-typert-registry'
import { workspaceFileDownloadUrl } from '@deepseek-ai/dsh-util-workspace-path'
import { describe, expect, it } from 'vitest'
import WorkspaceFiles, { WORKSPACE_FILE_DOWNLOAD_PATH } from '../src/index.ts'

interface CapturedRoute {
  readonly methods: readonly string[]
  readonly fetch: (request: Request) => Promise<Response>
}

const CAPS = {
  maxBytes: 1024,
  maxFileBytes: 64,
  maxLines: 100,
  maxEntries: 100,
}

/**
 * Boot the service over a real local backend with a capturing connection
 * carrier, and create one live Session whose workspace is the temp root.
 * @param prefix - temp directory prefix naming the case.
 * @returns the harness; dispose it in the case's finally.
 */
async function openDownloadHarness(prefix: string) {
  const root = await mkdtemp(join(tmpdir(), prefix))
  const workspace = join(root, 'workspace')
  const outside = join(root, 'outside')
  await mkdir(workspace, { recursive: true })
  await mkdir(outside, { recursive: true })
  const ctx = new Context()
  const routes = new Map<string, CapturedRoute>()
  const disposers: (() => void)[] = []
  const fs = await ctx.plugin(LocalFileSystem, { cwd: workspace })
  ctx.provide('sandboxPolicy', {
    workspaceRoot: workspace,
    resolve: () => ({ mode: 'workspace-write', workspaceRoot: workspace }),
  } as never)
  ctx.provide('connection', {
    fetch: {
      register: (route: { path: string; methods: readonly string[]; fetch: (request: Request) => Promise<Response> }) => {
        routes.set(route.path, { methods: route.methods, fetch: route.fetch })
        const dispose = () => { routes.delete(route.path) }
        disposers.push(dispose)
        return dispose
      },
    },
  } as never)
  const sessions = await ctx.plugin(SessionStore)
  const typert = await ctx.plugin(TypertRegistry)
  const workspaceFiles = await ctx.plugin(WorkspaceFiles, CAPS)
  const sessionId = SessionId('download-session')
  ctx.sessions.create(sessionId, { meta: { cwd: workspace, origin: 'subagent' } })
  return {
    root,
    workspace,
    outside,
    sessionId,
    fetch: async (url: string, init?: RequestInit) => {
      const route = routes.get(WORKSPACE_FILE_DOWNLOAD_PATH)
      if (route === undefined) throw new Error('the download route is not registered')
      return route.fetch(new Request(url, init))
    },
    route: () => routes.get(WORKSPACE_FILE_DOWNLOAD_PATH),
    disposers,
    dispose: async () => {
      await workspaceFiles.dispose()
      await fs.dispose()
      await sessions.dispose()
      await typert.dispose()
      await rm(root, { recursive: true, force: true })
    },
  }
}

describe('Workspace file download route', () => {
  it('serves a workspace file as an attachment with the owning Session', async () => {
    const harness = await openDownloadHarness('wfdl-')
    try {
      const route = harness.route()
      expect(route?.methods).toEqual(['GET', 'HEAD'])
      // The Host registration and the browser-facing URL name one route.
      expect(new URL(workspaceFileDownloadUrl('https://host/tools/dsh/', 's', 'x')!).pathname
        .endsWith(WORKSPACE_FILE_DOWNLOAD_PATH)).toBe(true)
      await mkdir(join(harness.workspace, 'dir'))
      await writeFile(join(harness.workspace, 'dir', 'hello.txt'), 'hello\n')
      const url = `http://h${WORKSPACE_FILE_DOWNLOAD_PATH}?sessionId=${harness.sessionId}&path=dir/hello.txt`
      const response = await harness.fetch(url)
      expect(response.status).toBe(200)
      expect(response.headers.get('content-type')).toBe('application/octet-stream')
      expect(response.headers.get('content-disposition')).toBe('attachment; filename="hello.txt"')
      expect(response.headers.get('cache-control')).toBe('private, no-store')
      expect(response.headers.get('x-content-type-options')).toBe('nosniff')
      expect(response.headers.get('content-length')).toBe('6')
      expect(new TextDecoder().decode(new Uint8Array(await response.arrayBuffer()))).toBe('hello\n')
      const head = await harness.fetch(url, { method: 'HEAD' })
      expect(head.status).toBe(200)
      expect(head.headers.get('content-disposition')).toBe('attachment; filename="hello.txt"')
      expect(head.headers.get('content-length')).toBe('6')
      expect(new Uint8Array(await head.arrayBuffer())).toHaveLength(0)
      // An absolute path outside the workspace downloads like its RPC read.
      await writeFile(join(harness.outside, 'outside.txt'), 'outside\n')
      const outside = await harness.fetch(
        `http://h${WORKSPACE_FILE_DOWNLOAD_PATH}?sessionId=${harness.sessionId}&path=${encodeURIComponent(join(harness.outside, 'outside.txt'))}`,
      )
      expect(outside.status).toBe(200)
      expect(outside.headers.get('content-disposition')).toBe('attachment; filename="outside.txt"')
      // A non-ASCII name carries an RFC 5987 form over an ASCII fallback.
      await writeFile(join(harness.workspace, '测试 文件.txt'), 'cn\n')
      const named = await harness.fetch(
        `http://h${WORKSPACE_FILE_DOWNLOAD_PATH}?sessionId=${harness.sessionId}&path=${encodeURIComponent('测试 文件.txt')}`,
      )
      expect(named.status).toBe(200)
      expect(named.headers.get('content-disposition'))
        .toBe(`attachment; filename="__ __.txt"; filename*=UTF-8''${encodeURIComponent('测试 文件.txt')}`)
      harness.disposers.forEach(dispose => dispose())
      expect(harness.route()).toBeUndefined()
    } finally {
      await harness.dispose()
    }
  })

  it('answers invalid, missing, foreign, and oversized requests without leaking errors', async () => {
    const harness = await openDownloadHarness('wfdl-fail-')
    try {
      await writeFile(join(harness.workspace, 'small.txt'), 'tiny\n')
      await writeFile(join(harness.workspace, 'big.txt'), 'x'.repeat(CAPS.maxFileBytes + 1))
      await mkdir(join(harness.workspace, 'a-directory'))
      const base = `http://h${WORKSPACE_FILE_DOWNLOAD_PATH}?`
      const missing = await harness.fetch(`${base}path=small.txt`)
      expect(missing.status).toBe(400)
      const nul = await harness.fetch(
        `${base}sessionId=${harness.sessionId}&path=small%00.txt`,
      )
      expect(nul.status).toBe(400)
      const unknownSession = await harness.fetch(
        `${base}sessionId=${SessionId('never-created')}&path=small.txt`,
      )
      expect(unknownSession.status).toBe(404)
      const absent = await harness.fetch(
        `${base}sessionId=${harness.sessionId}&path=absent.txt`,
      )
      expect(absent.status).toBe(404)
      const directory = await harness.fetch(
        `${base}sessionId=${harness.sessionId}&path=a-directory`,
      )
      expect(directory.status).toBe(403)
      const oversized = await harness.fetch(
        `${base}sessionId=${harness.sessionId}&path=big.txt`,
      )
      expect(oversized.status).toBe(413)
      expect(await oversized.text()).toBe(`"big.txt" exceeds the ${CAPS.maxFileBytes} byte download cap`)
      const headMissing = await harness.fetch(`${base}path=small.txt`, { method: 'HEAD' })
      expect(headMissing.status).toBe(400)
      expect(new Uint8Array(await headMissing.arrayBuffer())).toHaveLength(0)
    } finally {
      await harness.dispose()
    }
  })
})
