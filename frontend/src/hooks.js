import { useCallback, useEffect, useState } from 'react'

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
