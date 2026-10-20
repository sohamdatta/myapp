import { useCallback, useEffect, useState } from 'react'

/**
 * Each page has its own address (/my-leave, /payroll), so the browser's Back and Forward buttons,
 * reloading, bookmarks and links sent to a colleague all work. The page id is the address.
 */
export function pathFor(page) {
  return page ? `/${page}` : '/'
}

/** The page the address names, or null for the root address. */
export function pageFromLocation() {
  const id = window.location.pathname.replace(/^\/+|\/+$/g, '')
  return id || null
}

/** Starts the next signed-in person at the root, not on the page the last one left open. */
export function resetLocation() {
  if (window.location.pathname !== '/') window.history.replaceState(null, '', '/' + window.location.search)
}

/**
 * The page shown, kept in step with the address. A page the person may not see (a link from
 * someone with other roles, or a page their roles lost) falls back to their first page.
 */
export function usePage(allowed, fallback) {
  const pick = useCallback(
    (wanted) => (wanted && allowed.includes(wanted) ? wanted : fallback),
    // The allowed pages only change when the Workspace remounts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [fallback, allowed.join(' ')],
  )
  const [page, setPage] = useState(() => pick(pageFromLocation()))

  // The address should name the page actually shown, without adding a history entry for the fix-up.
  useEffect(() => {
    if (pageFromLocation() !== page) window.history.replaceState(null, '', pathFor(page) + window.location.search)
  }, [page])

  useEffect(() => {
    const onPop = () => setPage(pick(pageFromLocation()))
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [pick])

  const navigate = useCallback(
    (next) => {
      const target = pick(next)
      if (target !== pageFromLocation()) window.history.pushState(null, '', pathFor(target))
      setPage(target)
    },
    [pick],
  )

  return [page, navigate]
}

/** A plain left click on a link: the app handles it. Anything else (new tab, new window) is the browser's. */
export function isPlainClick(event) {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey
}
