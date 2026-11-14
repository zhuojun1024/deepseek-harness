# Agent Note: Download workspace files through an owner-only attachment route

Status: implemented

English | [中文](2026-11-14-workspace-file-download-route.zh.md)

## Problem

The file menu offered no way to save a workspace file to the device that displays the GUI. Two existing channels cannot carry the gesture:

| Channel | Behavior | Why it is not enough |
|---|---|---|
| `readBytes` Remote | Returns `Uint8Array` through the Gateway's multipart answer | It is a data channel: every save would first materialize the whole file in browser memory |
| `BrowserFileActions.save` | `fetch` the source, build a `Blob`, click an `a[download]` on a `blob:` object URL | The URL scheme is `blob:`. A shell WebView download listener rejects `blob:` and `data:` URLs, and a browser cannot name the file beyond the anchor hint, so the answer is not a download anywhere but a page memory copy |

A third surface was available and unused: `/api/file` serves file bytes as a document, with no `Content-Disposition`. Pointing a download at it would still render in the WebView rather than save.

## Decision

Add one Host route and one menu action.

| Item | Contract |
|---|---|
| Registration | `GET`/`HEAD /api/workspace-file/download` on the Connection Fetch carrier (`ctx.connection.fetch.register`), exact path, buffered request body, registered by `WorkspaceFiles` through `ctx.inject(['connection'], …)` so a profile without the Web carrier keeps the service working |
| Authentication | The carrier's `admit` runs first: Host/Origin fence, signed browser cookie, and the owner-only check exact Fetch routes already apply. A non-owner API credential receives 403 before the handler runs |
| Identity | `sessionId` names the Session whose header-derived workspace root resolves the path; a missing or unknown Session is 400 or 404 |
| Read authority | The same gates as `readBytes`: `lstat` first, regular file only, composed-filesystem read authority (paths outside the workspace are readable), and the `maxFileBytes` cap |
| Answer | 200 with the bytes, `Content-Disposition: attachment`, `Content-Type: application/octet-stream`, `Content-Length`, `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`; a HEAD request answers the same headers without a body |
| Filename | The path's final segment; a name outside printable ASCII adds an RFC 5987 `filename*` form over an ASCII fallback |
| Refusals | 400 for a missing or malformed query, 404 for an unknown Session or absent file, 403 for a non-regular file or a permission denial, 413 above `maxFileBytes`, 499 for cancellation; the body names the failure code, never a host path |
| Client | The `file` menu's download action probes the route with `HEAD`, then clicks an `a[download]` on the route URL, so the browser and a shell download listener both save it. The image menu keeps the `BrowserFileActions` path, whose source is usually a URL rather than a workspace path |

### Implementation

| Package | Realization |
|---|---|
| `api/workspace-files` | `src/routes.ts` owns the absolute path; `WorkspaceFiles.serveDownload` answers it; `sessionScope` is the header-derived scope every read already uses, now shared by the lookup registration and the route; the `WorkspaceFilesConnection` interface keeps the carrier's browser-face types out of this Host project |
| `util/workspace-path` | `workspaceFileDownloadUrl(base, sessionId, path)` builds the document-relative URL beside `fileMediaUrl` |
| `client/ui-file-actions` | `startAttachmentDownload(url, filename)` clicks the anchor; the `file` menu's download item probes then navigates |

## Alternatives considered

- **Teach the shell to accept `blob:`/`data:` URLs.** That adds a JavaScript bridge and moves the file through base64 across it, so a 30 MiB file costs twice that in memory, and it fixes only the shell while a plain mobile browser still receives its own `blob:` copy.
- **Teach the shell to fetch an `/api/file` URL itself.** Still a shell-only change, still no `Content-Disposition`, and it duplicates the cookie handling the download listener already performs.
- **Add `Content-Disposition` to `/api/file`.** That route serves inline document previews; an attachment header would make every preview download instead of render.
- **Stream the file instead of reading it whole.** `ctx.fs.readBytes` is the only bounded read the service owns, and `maxFileBytes` already bounds it; a streaming answer would need a new filesystem seam operation for one route.
- **Put the route on the Gateway Remote.** A Remote answer is data for JavaScript, not a browser download, so the same memory cost returns.

## Consequences

- The download gesture now needs no Android shell change: a shell download listener already takes `http(s)` URLs with the WebView session cookie, and the system download manager names the file from `Content-Disposition`.
- Files above `maxFileBytes` (default 32 MiB) cannot be downloaded; the menu action reports the refusal instead of saving a truncated file.
- The route reads the whole file into Host memory once before answering, so a deployment with a large `maxFileBytes` accepts that per-request cost.
- A download is authorized by the same browser session as the rest of the GUI, so any credential able to open the GUI can download what the GUI already previews; the route adds no authority.
- The route bypasses the Gateway, so its path is part of the carrier's contract: `workspaceFileDownloadUrl` and `WORKSPACE_FILE_DOWNLOAD_PATH` must keep the same spelling, which the route spec asserts.
