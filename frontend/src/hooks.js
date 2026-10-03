import { useCallback, useEffect, useState } from 'react'

/** The current time, refreshed every half minute, for figures that move while the page is open. */
export function useNow() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30000)
    return () => clearInterval(timer)
  }, [])
  return now
}

/** Loads data on mount and whenever reload() is called. */
export function useLoad(loader) {
  const [state, setState] = useState({ data: null, error: '', loading: true })
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let cancelled = false
    loader()
      .then((data) => {
        if (!cancelled) setState({ data, error: '', loading: false })
      })
      .catch((err) => {
        if (!cancelled) setState({ data: null, error: err.message, loading: false })
      })
    return () => {
      cancelled = true
    }
    // The loader is a stable API function; version is what triggers a reload.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [version])

  const reload = useCallback(() => setVersion((v) => v + 1), [])
  return { ...state, reload }
}

/** Runs an action, tracking whether it is in flight and what went wrong. */
export function useAction() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function run(action) {
    setBusy(true)
    setError('')
    try {
      return await action()
    } catch (err) {
      setError(err.message)
      return undefined
    } finally {
      setBusy(false)
    }
  }

  return { busy, error, run, clearError: () => setError('') }
}

export function formatDate(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** A time of day, in the given time zone when one is known, otherwise the browser's. */
export function formatTime(iso, timeZone) {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', timeZone })
}

/** A calendar date such as 2026-10-03, shown with its weekday. */
export function formatDay(date) {
  if (!date) return '—'
  return new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })
}

export function formatMinutes(minutes) {
  if (!minutes) return '—'
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`
}

/** Today's date, or this month, as the browser sees it: 2026-10-03 or 2026-10. */
export function localToday() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

export const localMonth = () => localToday().slice(0, 7)

/** A month such as 2026-10, shown as October 2026. */
export function formatMonth(month) {
  return new Date(`${month}-01T00:00:00`).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
}

/** The date a number of days before or after a date, both as 2026-10-03. */
export function shiftDay(date, days) {
  const d = new Date(`${date}T12:00:00`)
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** What to call a day that is still going: working, not in yet, or null when its stored result stands. */
export function liveStatus(day, isToday) {
  if (!isToday) return null
  // Punched in and not yet out, whatever kind of day it is.
  if (day.incomplete && day.firstIn && !['on_duty', 'work_from_home'].includes(day.status)) return 'working'
  if (['absent', 'half_day'].includes(day.status) && !day.firstIn) return 'not in yet'
  return null
}
