import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rolldownOptions: {
      output: {
        // React changes far less often than the app, so it gets its own file: after an app update,
        // browsers keep the cached copy of React and download only what changed.
        codeSplitting: {
          groups: [{ name: 'react', test: /[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/ }],
        },
      },
    },
  },
  // Unit and component tests: `npm test`; `npm run test:coverage` adds a coverage report.
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.js'],
    include: ['src/**/*.test.{js,jsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,jsx}'],
      exclude: ['src/test/**', 'src/**/*.test.{js,jsx}', 'src/main.jsx'],
      reporter: ['text-summary', 'json-summary', 'html'],
    },
  },
  server: {
    host: true,
    port: 5173,
    strictPort: true,
    // Allow the preview address that GitHub Codespaces forwards the port to.
    allowedHosts: ['.app.github.dev', '.github.dev'],
    // Send API calls to the Spring Boot backend, so the browser only talks to one address.
    proxy: {
      '/api': {
        target: process.env.BACKEND_URL || 'http://localhost:8080',
        changeOrigin: true,
        // Pass on the address the browser connected from, for the attendance office-network check.
        xfwd: true,
      },
    },
  },
})
