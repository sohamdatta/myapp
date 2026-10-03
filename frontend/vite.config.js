import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
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
