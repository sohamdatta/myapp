import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource/fira-sans/400.css'
import '@fontsource/fira-sans/500.css'
import '@fontsource/fira-sans/600.css'
import '@fontsource/fira-sans/700.css'
import '@fontsource/fira-code/400.css'
import '@fontsource/fira-code/500.css'
import '@fontsource/fira-code/600.css'
import './index.css'
import App from './App.jsx'
import { ConfirmProvider, ToastProvider } from './feedback.jsx'
import { preloadLikely } from './pageRegistry.js'
import RegionBoundary from './RegionBoundary.jsx'
import { watchWindowErrors } from './errors.js'
import { pageFromLocation } from './router.js'

preloadLikely()
watchWindowErrors(() => pageFromLocation() ?? 'root')

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ToastProvider>
      <ConfirmProvider>
        <RegionBoundary region="app" page="app">
          <App />
        </RegionBoundary>
      </ConfirmProvider>
    </ToastProvider>
  </StrictMode>,
)
