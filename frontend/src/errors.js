// Reports a crash the browser caught to the backend (POST /api/client-errors), so a page that breaks is
// heard of before a customer says so. What is sent: the page id, the part of the screen, a short message
// and the build. The server blanks out email addresses and ids, keeps no user or Organization, and limits
// how many it takes. A failure here is never shown: reporting must not become a second crash.

const REPEAT_AFTER_MS = 60_000
const recent = new Map()

function pageId(value) {
  return typeof value === 'string' && /^[a-z0-9_-]{1,40}$/.test(value) ? value : 'unknown'
}

export function reportClientError({ page, region, error }) {
  try {
    const message = String(error?.message ?? error ?? '').slice(0, 300)
    if (!message) return
    const key = `${page}|${region}|${message}`
    const now = Date.now()
    if (now - (recent.get(key) ?? 0) < REPEAT_AFTER_MS) return   // a loop of renders would otherwise flood the server
    recent.set(key, now)
    if (recent.size > 100) recent.delete(recent.keys().next().value)
    fetch('/api/client-errors', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ page: pageId(page), region: pageId(region), message, build: import.meta.env.VITE_BUILD || null }),
      keepalive: true,
    }).catch(() => {})
  } catch {
    // Reporting is best effort.
  }
}

/** Errors that no boundary sees: in event handlers, timers and promises. */
export function watchWindowErrors(currentPage) {
  window.addEventListener('error', (event) => {
    // "Script error." has no detail (another origin's script); an error from a file that is not ours is a browser
    // extension or an injected script. Neither is a fault in this app.
    if (event.message === 'Script error.' || (event.filename && !event.filename.startsWith(window.location.origin))) return
    reportClientError({ page: currentPage(), region: 'window', error: event.error ?? event.message })
  })
  window.addEventListener('unhandledrejection', (event) => {
    reportClientError({ page: currentPage(), region: 'window', error: event.reason })
  })
}
