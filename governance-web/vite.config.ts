import { fileURLToPath, URL } from 'node:url'
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const keyFile = env.GOVERNANCE_DEV_HTTPS_KEY_FILE?.trim()
  const certificateFile = env.GOVERNANCE_DEV_HTTPS_CERT_FILE?.trim()
  if (Boolean(keyFile) !== Boolean(certificateFile)) {
    throw new Error('Both GOVERNANCE_DEV_HTTPS_KEY_FILE and GOVERNANCE_DEV_HTTPS_CERT_FILE are required for HTTPS')
  }

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: env.GOVERNANCE_DEV_HTTPS_HOST || 'localhost',
      port: Number(env.GOVERNANCE_DEV_HTTPS_PORT || 5173),
      https: keyFile && certificateFile
        ? { key: readFileSync(keyFile), cert: readFileSync(certificateFile) }
        : undefined,
      proxy: {
        '/auth/admin': {
          target: env.GOVERNANCE_GATEWAY_TARGET || 'http://127.0.0.1:8080',
          changeOrigin: false,
        },
        '/animal/api/v1/admin/community-review': {
          target: env.GOVERNANCE_GATEWAY_TARGET || 'http://127.0.0.1:8080',
          changeOrigin: false,
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      clearMocks: true,
    },
  }
})
