import { existsSync } from 'node:fs'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { missionServiceProxy } from './devProxy.ts'

// Without local backend credentials the console still starts; only demo mode (`?demo`) has data then.
const hasBackend = existsSync(new URL('../.local/msa.env', import.meta.url))

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: hasBackend ? {
      '/fe-api/control': missionServiceProxy('/fe-api/control', 8107),
      '/fe-api/product': missionServiceProxy('/fe-api/product', 8112),
      '/fe-api/reference': missionServiceProxy('/fe-api/reference', 8105),
      '/fe-api/flight': missionServiceProxy('/fe-api/flight', 8103),
      '/fe-api/tasking': missionServiceProxy('/fe-api/tasking', 8101),
      '/fe-api/planning': missionServiceProxy('/fe-api/planning', 8102),
      '/fe-api/monitoring': missionServiceProxy('/fe-api/monitoring', 8109),
      '/fe-api/anomaly': missionServiceProxy('/fe-api/anomaly', 8110),
    } : undefined,
  },
})
