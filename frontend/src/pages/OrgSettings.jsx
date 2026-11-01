import { useRef, useState } from 'react'
import { branding, certificateLayout } from '../api.js'
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
    <>
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
    {(me.modules || []).includes('payroll') && b.canChange && <CertificateLayoutPanel hasLogo={Boolean(current)} />}
    </>
  )
}

const fromLayout = (l) => ({
  headerLine: l.headerLine || '',
  footerText: l.footerText || '',
  signatoryName: l.signatoryName || '',
  signatoryDesignation: l.signatoryDesignation || '',
  showLogo: l.showLogo,
})

/**
 * How the Form 130 Part B certificate looks: a header line, footer text, a signatory block and whether the logo shows.
 * The figures and the statutory wording are not part of it. Each save is a new version; a draft certificate picks it up when
 * built again, and a released one never changes.
 */
function CertificateLayoutPanel({ hasLogo }) {
  const loaded = useLoad(certificateLayout.get)
  const [form, setForm] = useState(null)
  const [view, setView] = useState(null)
  const [saved, setSaved] = useState('')
  const { busy, error, run } = useAction()
  const v = view || loaded.data
  if (loaded.loading && !v) return <p className="muted">Loading…</p>
  if (loaded.error) return <ErrorNote>{loaded.error}</ErrorNote>
  const start = fromLayout(v.current)
  const f = form || start
  const set = (key) => (e) => {
    setSaved('')
    setForm({ ...f, [key]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  }
  const changed = JSON.stringify(f) !== JSON.stringify(start)
  const typed = { ...f }
  return (
    <section className="panel" data-testid="certificate-layout">
      <h3>Tax certificate layout</h3>
      <p className="hint">
        Shapes the Form 130 Part B certificate your employees receive: a line above the title, text at the foot of the page, who signs, and
        whether your logo shows. The figures and the statutory wording cannot be changed here. Use Western European letters, digits and
        punctuation: the certificate&apos;s font cannot print other scripts. A saved change applies to certificates built afterwards; drafts pick
        it up when built again, and a released certificate never changes.
      </p>
      <p className="hint" data-testid="layout-version">
        {v.current.version === 0 ? 'Not set yet: certificates use the plain layout.' : `Version ${v.current.version}, saved ${formatDate(v.current.savedAt)}.`}
      </p>
      <ErrorNote>{error}</ErrorNote>
      {saved && <p className="notice">{saved}</p>}
      <div className="form-grid">
        <label htmlFor="cl-header">Header line</label>
        <input id="cl-header" maxLength={120} value={f.headerLine} onChange={set('headerLine')} />
        <label htmlFor="cl-footer">Footer text</label>
        <textarea id="cl-footer" maxLength={300} rows={3} value={f.footerText} onChange={set('footerText')} />
        <label htmlFor="cl-name">Signatory&apos;s name</label>
        <input id="cl-name" maxLength={80} value={f.signatoryName} onChange={set('signatoryName')} />
        <label htmlFor="cl-designation">Signatory&apos;s designation</label>
        <input id="cl-designation" maxLength={80} value={f.signatoryDesignation} onChange={set('signatoryDesignation')} />
        <label htmlFor="cl-logo">Show the logo</label>
        <span>
          <input id="cl-logo" type="checkbox" checked={f.showLogo} onChange={set('showLogo')} disabled={!hasLogo} />{' '}
          <span className="hint">{hasLogo ? 'Your logo, set above, at the top left.' : 'Upload a logo above to show it here.'}</span>
        </span>
      </div>
      <div className="form-actions">
        <button
          type="button"
          className="secondary"
          disabled={busy}
          onClick={async () => {
            // Opened first, while the click still counts, so a pop-up blocker does not swallow it; pointed at the sample once it is made.
            const tab = window.open('', '_blank')
            if (!tab) {
              setSaved('')
              await run(async () => {
                throw new Error('Your browser blocked the sample from opening. Allow pop-ups for this site and try again.')
              })
              return
            }
            const blob = await run(() => certificateLayout.preview(typed))
            if (blob) {
              const url = URL.createObjectURL(blob)
              tab.location.href = url
              setTimeout(() => URL.revokeObjectURL(url), 60000)
            } else {
              tab.close()
            }
          }}
        >
          Preview a sample
        </button>
        <button
          type="button"
          className="primary"
          disabled={busy || !changed}
          onClick={async () => {
            setSaved('')
            const next = await run(() => certificateLayout.save(typed))
            if (next) {
              setView(next)
              setForm(null)
              setSaved(next.current.version === v.current.version ? 'Nothing changed.' : `Saved as version ${next.current.version}. Build the certificates again to use it on drafts.`)
            }
          }}
        >
          Save the layout
        </button>
        {changed && (
          <button type="button" className="secondary" disabled={busy} onClick={() => setForm(null)}>
            Discard changes
          </button>
        )}
      </div>
    </section>
  )
}
