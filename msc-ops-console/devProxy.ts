// Node-only (runs in the Vite dev server process, never in the browser bundle).
// Injects local Basic-Auth credentials so the browser never sees SERVICE/ADMIN secrets.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { ProxyOptions } from 'vite'

type Role = 'requester' | 'operator1' | 'operator2' | 'admin' | 'service'

function loadLocalEnv(): Record<string, string> {
  const path = resolve(import.meta.dirname, '../.local/msa.env')
  const text = readFileSync(path, 'utf8')
  const env: Record<string, string> = {}
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue
    const idx = trimmed.indexOf('=')
    env[trimmed.slice(0, idx)] = trimmed.slice(idx + 1)
  }
  return env
}

function basicAuthHeader(user: Role, password: string): string {
  return 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64')
}

// Least-privilege default per prefix; only the specific mutations below get elevated roles.
// Deliberately explicit per path/method (not a broadened prefix default) so a new backend
// mutation never silently inherits an elevated role just by matching a prefix.
type RoleRule = [RegExp, string, Role]

const referenceRules: RoleRule[] = [
  [/^\/api\/tracked-satellites\/\d+\/refresh$/, 'POST', 'admin'],
]

const flightRules: RoleRule[] = [
  [/^\/api\/public-orbits\/import$/, 'POST', 'admin'],
  [/^\/api\/public-orbits\/[^/]+\/predictions$/, 'POST', 'operator1'],
  [/^\/api\/public-orbits\/[^/]+\/access-predictions$/, 'POST', 'operator1'],
  [/^\/internal\/time\/utc-to-tai$/, 'POST', 'service'],
]

const taskingRules: RoleRule[] = []

// /api/planning/requests/{id} has no @PreAuthorize (ownership/elevation is checked inside the
// handler against the tasking-owned request), so 'requester' is fine as the default. The
// input-attempts read requires ADMIN/OPERATOR/SERVICE explicitly.
const planningRules: RoleRule[] = [
  [/^\/api\/planning\/runs\/[^/]+\/camera\/\d+$/, 'GET', 'operator1'],
  [/^\/api\/planning\/input-attempts\/[^/]+\/runs$/, 'GET', 'operator1'],
  [/^\/api\/planning\/runs\/[^/]+(?:\/(?:resources|camera-work|illumination-work))?$/, 'GET', 'operator1'],
  [/^\/api\/planning\/input-attempts\/[^/]+$/, 'GET', 'operator1'],
]

const monitoringRules: RoleRule[] = [[/^\/api\/telemetry-bindings$/, 'POST', 'admin']]

// SafetyApi requires OPERATOR/ADMIN/SERVICE on every endpoint (no anonymous/requester path
// exists), so 'requester' cannot be this prefix's default.
const anomalyRules: RoleRule[] = [[/^\/api\/safety-policies$/, 'POST', 'admin']]

function roleFor(rules: RoleRule[], defaultRole: Role, method: string, path: string): Role {
  for (const [pattern, ruleMethod, role] of rules) {
    if (method === ruleMethod && pattern.test(path)) return role
  }
  return defaultRole
}

const prefixConfig: Record<string, { rules: RoleRule[]; defaultRole: Role }> = {
  '/fe-api/control': { rules: [[/^\/api\/command-loads\/[^/]+\/simulation-(?:release|delivery|execution)$/, 'GET', 'operator1']], defaultRole: 'requester' },
  '/fe-api/product': { rules: [[/^\/api\/products\/simulation-source-packages\/[^/]+$/, 'GET', 'operator1']], defaultRole: 'requester' },
  '/fe-api/reference': { rules: referenceRules, defaultRole: 'requester' },
  '/fe-api/flight': { rules: flightRules, defaultRole: 'requester' },
  '/fe-api/tasking': { rules: taskingRules, defaultRole: 'requester' },
  '/fe-api/planning': { rules: planningRules, defaultRole: 'requester' },
  '/fe-api/monitoring': { rules: monitoringRules, defaultRole: 'requester' },
  '/fe-api/anomaly': { rules: anomalyRules, defaultRole: 'operator1' },
}

// Exported (pure, no env/network access) so role-mapping regressions are unit-testable without
// requiring .local/msa.env or a running backend.
export function resolveRole(prefix: string, method: string, path: string, actorHeader?: string): Role {
  const config = prefixConfig[prefix]
  if (!config) throw new Error(`No role configuration for proxy prefix ${prefix}`)
  let role = roleFor(config.rules, config.defaultRole, method, path)
  if (prefix === '/fe-api/anomaly' && actorHeader === 'operator2') role = 'operator2'
  return role
}

export function missionServiceProxy(prefix: string, port: number): ProxyOptions {
  const env = loadLocalEnv()
  const passwords: Record<Role, string> = {
    requester: env.MSC_LOCAL_REQUESTER_PASSWORD,
    operator1: env.MSC_LOCAL_OPERATOR_PASSWORD,
    operator2: env.MSC_LOCAL_OPERATOR_PASSWORD,
    admin: env.MSC_LOCAL_ADMIN_PASSWORD,
    service: env.MSC_SERVICE_PASSWORD,
  }
  const config = prefixConfig[prefix]
  if (!config) throw new Error(`No role configuration for proxy prefix ${prefix}`)
  return {
    target: `http://127.0.0.1:${port}`,
    changeOrigin: true,
    rewrite: (path) => path.replace(prefix, ''),
    configure(proxy) {
      proxy.on('proxyReq', (proxyReq, req) => {
        const backendPath = (req.url ?? '').replace(prefix, '').split('?')[0]
        // The FE picks which local operator identity to use for anomaly recovery approvals via
        // this header (never forwarded upstream); every other request uses the fixed per-path role.
        const requestedActor = req.headers['x-msc-actor']
        const role = resolveRole(
          prefix,
          req.method ?? 'GET',
          backendPath,
          typeof requestedActor === 'string' ? requestedActor : undefined,
        )
        proxyReq.removeHeader('x-msc-actor')
        proxyReq.setHeader('Authorization', basicAuthHeader(role, passwords[role]))
      })
    },
  }
}
