import { existsSync } from 'node:fs'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'
import { missionServiceProxy } from './devProxy.ts'

// Without local backend credentials the console still starts; only demo mode (`?demo`) has data then.
const hasBackend = existsSync(new URL('../.local/msa.env', import.meta.url))

// Without a proxy, /fe-api would fall through to the SPA fallback and return index.html with 200;
// answer 503 instead so live screens show "cannot connect" rather than a JSON parse error.
const noBackend: Plugin = {
  name: 'msc-no-backend',
  configureServer(server) {
    server.middlewares.use('/fe-api', (_req, res) => {
      res.statusCode = 503
      res.setHeader('Content-Type', 'application/json')
      res.end(JSON.stringify({ message: '백엔드 설정(.local/msa.env)이 없습니다. 데모 모드(?demo)를 사용하세요.' }))
    })
  },
}

// https://vite.dev/config/
export default defineConfig({
  plugins: hasBackend ? [react()] : [react(), noBackend],
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
      '/fe-api/mission': missionServiceProxy('/fe-api/mission', 8104),
      '/fe-api/ground': missionServiceProxy('/fe-api/ground', 8106),
      '/fe-api/acquisition': missionServiceProxy('/fe-api/acquisition', 8111),
      '/fe-api/simulator': missionServiceProxy('/fe-api/simulator', 8114),
    } : undefined,
  },
})
