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
/** A moment in a narrow column: "Today, 10:02", "28 Oct, 09:56", or "3 Mar 2025" from another year. */
export function formatWhen(iso, now = new Date()) {
  if (!iso) return '—'
  const d = new Date(iso)
  const time = d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
  if (d.toDateString() === now.toDateString()) return `Today, ${time}`
  if (d.getFullYear() === now.getFullYear()) return `${d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}, ${time}`
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

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

/**
 * Today's date in India, as the server counts days: use it for a default the server checks against
 * its own today ("starts today or later"), so a browser on another time zone, or the hours just
 * after midnight when the UTC date is still yesterday, never proposes a day the server refuses.
 */
export function indiaToday() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date())
}

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
  // A present-by-default day is settled already: present, or as someone marked it.
  if ((day.trail || []).some((step) => step.startsWith('Present by default') || step.startsWith('Marked '))) return null
  // Punched in and not yet out, whatever kind of day it is.
  if (day.incomplete && day.firstIn && !['on_duty', 'work_from_home'].includes(day.status)) return 'working'
  if (['absent', 'half_day'].includes(day.status) && !day.firstIn) return 'not in yet'
  return null
}

/** A number of leave days: 1 day, 2.5 days, 0 days. */
export function formatDays(value) {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  return `${n} ${Math.abs(n) === 1 ? 'day' : 'days'}`
}

/** A date range as one date, or "3 Oct to 5 Oct", with the half when only half a day is meant. */
export function formatRange(from, to, half) {
  const range = !to || from === to ? formatDay(from) : `${formatDay(from)} to ${formatDay(to)}`
  return half ? `${range}, ${half} half` : range
}

/** An empty leave request form, and the request body made from one. */
export const newLeave = () => ({ leaveTypeId: '', fromDate: localToday(), toDate: localToday(), half: '', reason: '' })

export function leaveBody(value) {
  const single = !value.toDate || value.toDate === value.fromDate
  return {
    leaveTypeId: value.leaveTypeId,
    fromDate: value.fromDate,
    toDate: value.toDate || value.fromDate,
    half: single && value.half ? value.half : null,
    reason: value.reason,
  }
}

/** What to call a person in a sentence or a label: the name on their record, else their email. */
export const whoName = (person) => person.name || person.email

/** Lateness in words: "12 min", or "2 h 05 min" past an hour. */
export const formatLate = (minutes) => (minutes < 60 ? `${minutes} min` : formatMinutes(minutes))

/**
 * What one page asks the next to open when it sends the person there (an exit to start for an
 * employee, an exit to show). Read once by the page it was meant for.
 */
let pending = null
export const handoff = {
  set(value) {
    pending = value
  },
  take() {
    const value = pending
    pending = null
    return value
  },
}

/**
 * A number typed into a form, or null when the field is empty. Text that is not a number throws,
 * so the form shows "… must be a number" instead of quietly sending nothing (which the server reads
 * as "use the default"). Indian digit grouping (1,00,000) is accepted.
 */
export function numeric(value, what = 'This value', { whole = false } = {}) {
  if (value === '' || value === null || value === undefined) return null
  const text = String(value).replace(/,/g, '').trim()
  const n = text === '' ? NaN : Number(text)
  if (!Number.isFinite(n)) throw new Error(`${what} must be a number`)
  if (whole && !Number.isInteger(n)) throw new Error(`${what} must be a whole number`)
  return n
}

/** A whole number typed into a form (days, minutes, a position), or null when empty. */
export const wholeNumber = (value, what) => numeric(value, what, { whole: true })
