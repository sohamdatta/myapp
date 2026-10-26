import { Suspense } from 'react'
import RegionBoundary from './RegionBoundary.jsx'
import { registry } from './pageRegistry.js'

/** Grey bars in the shape of a page, while its code or first data arrives. */
function PageSkeleton() {
  return (
    <div className="skeleton" role="status" aria-busy="true" aria-label="Loading">
      <div className="skeleton-bar skeleton-wide" />
      <div className="skeleton-bar" />
      <div className="skeleton-bar" />
      <div className="skeleton-bar skeleton-short" />
    </div>
  )
}

/** The page the sidebar points at, for an Organization or the platform console. */
export default function PageView({ me, page: id, onNavigate, onEnteredSupport, onRefresh }) {
  if (!id) {
    return (
      <section className="panel">
        <h2>You are signed in</h2>
        <p>
          {me.email} is a member of {me.organization?.name}. There is nothing to show for your roles yet.
        </p>
      </section>
    )
  }
  const entry = registry(me)[id]
  if (!entry) return null
  const { Component: Page, props } = entry
  return (
    <RegionBoundary key={id} region="page" page={id}>
      <Suspense fallback={<PageSkeleton />}>
        <Page {...props({ me, page: id, onNavigate, onEnteredSupport, onRefresh })} />
      </Suspense>
    </RegionBoundary>
  )
}
