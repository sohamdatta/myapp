import { useEffect, useRef, useState } from 'react'
import { notifications } from './api.js'
import { formatDate } from './hooks.js'

/**
 * The bell: how many messages are unread, and the latest ones. Opening one
 * marks it read and goes to the page it is about. Checked on load and every
 * minute; reminders that have fallen due are made when it is checked.
 */
export default function Bell({ pages, onNavigate }) {
  const [data, setData] = useState({ unread: 0, items: [] })
  const [open, setOpen] = useState(false)
  const box = useRef(null)

  useEffect(() => {
    let cancelled = false
    const load = () =>
      notifications
        .list()
        .then((d) => !cancelled && setData(d))
        .catch(() => {})
    load()
    const timer = setInterval(load, 60000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [])

  useEffect(() => {
    if (!open) return undefined
    const close = (e) => box.current && !box.current.contains(e.target) && setOpen(false)
    const escape = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', escape)
    }
  }, [open])

  async function choose(item) {
    setOpen(false)
    if (!item.readAt) {
      try {
        const { unread } = await notifications.read([item.id])
        setData((d) => ({ unread, items: d.items.map((n) => (n.id === item.id ? { ...n, readAt: new Date().toISOString() } : n)) }))
      } catch {
        // stays unread; nothing else to do
      }
    }
    if (item.link && pages.includes(item.link)) onNavigate(item.link)
  }

  async function readAll() {
    try {
      await notifications.read([])
      setData((d) => ({ unread: 0, items: d.items.map((n) => ({ ...n, readAt: n.readAt || new Date().toISOString() })) }))
    } catch {
      // leave as it is
    }
  }

  return (
    <div className="bell" ref={box}>
      <button
        type="button"
        className="icon-button bell-button"
        aria-label={data.unread ? `Notifications, ${data.unread} unread` : 'Notifications'}
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {data.unread > 0 && <span className="bell-count">{data.unread > 99 ? '99+' : data.unread}</span>}
      </button>
      {open && (
        <div className="bell-panel" role="dialog" aria-label="Notifications">
          <div className="bell-head">
            <strong>Notifications</strong>
            {data.unread > 0 && (
              <button type="button" className="quiet plain" onClick={readAll}>
                Mark all read
              </button>
            )}
          </div>
          {data.items.length === 0 ? (
            <p className="hint bell-empty">Nothing yet.</p>
          ) : (
            <ul className="bell-list">
              {data.items.map((n) => (
                <li key={n.id}>
                  <button type="button" className={n.readAt ? 'bell-item' : 'bell-item bell-unread'} onClick={() => choose(n)}>
                    {n.subject && <span className="bell-subject">{n.subject}</span>}
                    <span className="bell-body">{n.body}</span>
                    <span className="hint">{formatDate(n.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
