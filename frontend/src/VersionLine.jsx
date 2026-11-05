import { useEffect, useState } from 'react'
import { APP, buildLabel, versionLabel } from './version.js'

/**
 * The build number, small and quiet, so a deployment can be told apart at a glance. It also asks the server
 * which build it runs: when that differs from these screens, the page is out of date and needs a reload.
 */
export default function VersionLine({ className = '' }) {
  const [server, setServer] = useState(null)
  useEffect(() => {
    let live = true
    fetch('/api/version')
      .then((r) => (r.ok ? r.json() : null))
      .then((v) => live && v && setServer(v))
      .catch(() => {})
    return () => {
      live = false
    }
  }, [])

  const mismatch = server && APP.commit !== 'dev' && server.commit !== 'dev' && server.commit !== APP.commit
  const title = `Screens: ${buildLabel(APP)}${APP.built ? `, built ${APP.built}` : ''}${server ? `\nServer: ${buildLabel(server)}${server.built ? `, built ${server.built}` : ''}` : ''}`
  return (
    <span className={`version-line ${className}`} title={title}>
      {versionLabel(APP)}
      {mismatch && (
        <>
          {' · '}
          <button type="button" className="version-reload" onClick={() => window.location.reload()}>
            Update available, reload
          </button>
        </>
      )}
    </span>
  )
}
