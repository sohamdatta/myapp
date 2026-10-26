import { useRef, useState } from 'react'
import { branding } from '../api.js'
import { formatDate, useAction, useLoad } from '../hooks.js'
import { ErrorNote } from '../ui.jsx'
import { PRODUCT, logoUrl } from '../brand/brand.js'
import NexvanceLogo from '../brand/NexvanceLogo.jsx'

/** Organization settings, for the Org Admin: the Organization's own logo, shown in place of the product's. */
export default function OrgSettings({ me, onRefresh }) {
  const loaded = useLoad(branding.get)
  const [view, setView] = useState(null)
  const [saved, setSaved] = useState('')
  const input = useRef(null)
  const { busy, error, run } = useAction()
  const b = view || loaded.data
  if (loaded.loading && !b) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  const current = logoUrl({ id: me.organization.id, logoVersion: b.logoVersion })
  const done = async (next, words) => {
    if (next) {
      setView(next)
      setSaved(words)
      onRefresh?.()
    }
  }
  return (
    <section className="panel" data-testid="branding">
      <h3>Logo</h3>
      <p className="hint">
        Your logo replaces the {PRODUCT.name} logo at the top of the sidebar for everyone in {me.organization.name}. Use a PNG or JPEG
        of at most 2 MB, ideally wide, on a transparent or white background; it is scaled to fit. The sign-in page keeps the{' '}
        {PRODUCT.name} logo, because people sign in before choosing an Organization.
      </p>
      <div className="logo-preview" data-testid="logo-preview">
        {current ? <img src={current} alt={`${me.organization.name} logo`} /> : <NexvanceLogo />}
        <span className="hint">
          {current ? `Your logo, ${b.logoWidth} × ${b.logoHeight} pixels, changed ${formatDate(b.updatedAt)}` : `No logo of your own: the ${PRODUCT.name} logo shows`}
        </span>
      </div>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">{saved}</p>}
      {b.canChange && (
        <div className="form-actions">
          <input
            ref={input}
            id="logo-file"
            aria-label="Logo file"
            type="file"
            accept="image/png,image/jpeg"
            className="sr-only"
            onChange={async (e) => {
              const file = e.target.files?.[0]
              e.target.value = ''
              if (file) done(await run(() => branding.uploadLogo(file)), 'Logo saved.')
            }}
          />
          <button type="button" className="primary" disabled={busy} onClick={() => input.current?.click()}>
            {current ? 'Change the logo' : 'Upload a logo'}
          </button>
          {current && (
            <button type="button" className="secondary" disabled={busy} onClick={async () => done(await run(branding.removeLogo), `Logo removed: the ${PRODUCT.name} logo shows again.`)}>
              Remove it
            </button>
          )}
        </div>
      )}
    </section>
  )
}
