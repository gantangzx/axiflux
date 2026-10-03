import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'

// Console SPA source lives here; the production build is emitted straight into
// the Spring Boot static resources dir so `mvn package` ships the built UI.
export default defineConfig(({ mode }) => {
  // Community (open-source) is the default; the enterprise build sets
  // VITE_EDITION=enterprise (e.g. via a .env.enterprise file or env var).
  const edition = process.env.VITE_EDITION ?? (mode === 'enterprise' ? 'enterprise' : 'community')
  const isCommunity = edition !== 'enterprise'
  const r = (p: string) => fileURLToPath(new URL(p, import.meta.url))

  // Intercept the extension-less './commercial' aggregator import (used by
  // App.tsx) and redirect it to the open-source stub. A resolveId hook matches
  // the raw import specifier reliably on every platform, unlike path regexes.
  const communityCommercialStub: Plugin = {
    name: 'community-commercial-stub',
    enforce: 'pre',
    resolveId(source, importer) {
      if (!importer || !importer.replace(/\\/g, '/').endsWith('/src/App.tsx')) return null
      if (source !== './commercial') return null
      return r('./src/commercial-stub.tsx')
    },
  }

  return {
    plugins: [react(), ...(isCommunity ? [communityCommercialStub] : [])],
    base: '/',
    build: {
      outDir: '../src/main/resources/static',
      emptyOutDir: true,
    },
    server: {
      port: 5173,
      proxy: {
        '/api': { target: 'http://localhost:8080', changeOrigin: true },
      },
    },
  }
})
