import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  define: {
    // sockjs-client uses Node.js's `global` which doesn't exist in browsers.
    // Map it to the standard `globalThis` — fixes "ReferenceError: global is not defined"
    global: 'globalThis',
  },
  build: {
    // Vite 8 (Rolldown) ships without esbuild. Its default lightningcss minifier
    // rejects certain CSS constructs. Disabling CSS minification is the safe
    // option — JS is still minified, only CSS remains uncompressed (tiny size difference).
    cssMinify: false,
  },
})
