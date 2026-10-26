import { Component } from 'react'
import { reportClientError } from './errors.js'

/** True when the page's code could not be downloaded (offline, or a new version replaced it) rather than crashed. */
function isLoadFailure(error) {
  const text = `${error?.name ?? ''} ${error?.message ?? ''}`
  return /dynamically imported module|Failed to fetch|Importing a module script failed|ChunkLoadError|Load failed/i.test(text)
}

/**
 * Keeps a crash inside the part of the screen it happened in, reports it, and offers a way out.
 * `region` names the part (page, nav, bell, app); `fallback` replaces the default message, and null shows nothing.
 */
export default class RegionBoundary extends Component {
  state = { failed: false, load: false }

  static getDerivedStateFromError(error) {
    return { failed: true, load: isLoadFailure(error) }
  }

  componentDidCatch(error) {
    reportClientError({ page: this.props.page, region: this.props.region, error })
  }

  render() {
    if (!this.state.failed) return this.props.children
    if ('fallback' in this.props) return this.props.fallback
    if (this.state.load) {
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
    return (
      <div className="panel" role="alert">
        <h2>Something went wrong on this page</h2>
        <p>It has been reported. You can try again, or open another page from the menu.</p>
        <button type="button" className="primary" onClick={() => this.setState({ failed: false, load: false })}>
          Try again
        </button>
      </div>
    )
  }
}
