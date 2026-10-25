import { Component, Suspense } from 'react'
import { registry } from './pageRegistry.js'

/** Grey bars in the shape of a page, while its code or first data arrives. */
function PageSkeleton() {
  return (
    <div className="skeleton" aria-busy="true" aria-label="Loading">
      <div className="skeleton-bar skeleton-wide" />
      <div className="skeleton-bar" />
      <div className="skeleton-bar" />
      <div className="skeleton-bar skeleton-short" />
    </div>
  )
}

/** When a page cannot be downloaded (offline, or a new version replaced it), say so and offer a way out. */
class PageLoadError extends Component {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div className="panel" role="alert">
        <h2>This page did not load</h2>
        <p>Check your connection. If the app was just updated, reloading fetches the new version.</p>
        <button type="button" className="primary" onClick={() => window.location.reload()}>
          Reload
        </button>
      </div>
    )
  }
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
    <PageLoadError key={id}>
      <Suspense fallback={<PageSkeleton />}>
        <Page {...props({ me, page: id, onNavigate, onEnteredSupport, onRefresh })} />
      </Suspense>
    </PageLoadError>
  )
}
