import { describe, expect, it } from 'vitest'
import { resolveRole } from '../devProxy'

describe('resolveRole', () => {
  it('anomaly prefix defaults to operator1, not requester (SafetyApi requires OPERATOR/ADMIN/SERVICE on every path)', () => {
    expect(resolveRole('/fe-api/anomaly', 'GET', '/api/safety/sim-spaceeye-t1')).toBe('operator1')
  })

  it('anomaly policy creation is admin-only even though the prefix default is operator1', () => {
    expect(resolveRole('/fe-api/anomaly', 'POST', '/api/safety-policies')).toBe('admin')
  })

  it('lets the FE choose the operator2 identity for two-person recovery approval', () => {
    expect(
      resolveRole('/fe-api/anomaly', 'POST', '/api/safety/sim-spaceeye-t1/recovery-approvals', 'operator2'),
    ).toBe('operator2')
  })

  it('ignores the actor header outside the anomaly prefix', () => {
    expect(resolveRole('/fe-api/tasking', 'POST', '/api/requests', 'operator2')).toBe('requester')
  })

  it('tasking defaults to requester for every path (ownership-checked server-side)', () => {
    expect(resolveRole('/fe-api/tasking', 'PUT', '/api/requests/abc')).toBe('requester')
    expect(resolveRole('/fe-api/tasking', 'POST', '/api/requests/abc/cancel')).toBe('requester')
  })

  it('flight access-prediction mutation requires operator1', () => {
    expect(
      resolveRole('/fe-api/flight', 'POST', '/api/public-orbits/abc-123/access-predictions'),
    ).toBe('operator1')
  })

  it('planning intake status defaults to requester, input-attempt read requires operator1', () => {
    expect(resolveRole('/fe-api/planning', 'GET', '/api/planning/requests/abc')).toBe('requester')
    expect(resolveRole('/fe-api/planning', 'GET', '/api/planning/input-attempts/xyz')).toBe('operator1')
  })

  it('monitoring binding registration is admin-only, reads default to requester', () => {
    expect(resolveRole('/fe-api/monitoring', 'POST', '/api/telemetry-bindings')).toBe('admin')
    expect(resolveRole('/fe-api/monitoring', 'GET', '/api/spacecraft-estimates/sim-spaceeye-t1')).toBe(
      'requester',
    )
  })

  it('V1 command steps use operator1, never admin or service', () => {
    for (const path of [
      '/api/command-loads/prepare',
      '/api/command-loads/l1/approvals',
      '/api/command-loads/l1/schedule-check',
      '/api/command-loads/l1/simulation-release',
      '/api/command-loads/l1/simulation-dispatch',
      '/api/command-loads/l1/simulation-reception',
      '/api/command-loads/l1/simulation-execution/reconcile',
    ])
      expect(resolveRole('/fe-api/control', 'POST', path)).toBe('operator1')
    expect(resolveRole('/fe-api/control', 'GET', '/api/simulation-execution-evidence/s/l')).toBe('operator1')
    expect(resolveRole('/fe-api/control', 'DELETE', '/api/command-loads/l1')).toBe('requester')
  })

  it('planning commit/downlink/solve, result registration and preview use operator1', () => {
    expect(resolveRole('/fe-api/planning', 'POST', '/api/planning/runs/r/simulation-commit')).toBe('operator1')
    expect(resolveRole('/fe-api/planning', 'POST', '/api/planning/runs/r/simulation-downlink')).toBe('operator1')
    expect(resolveRole('/fe-api/planning', 'POST', '/api/planning/runs/r/solve')).toBe('operator1')
    expect(resolveRole('/fe-api/tasking', 'POST', '/api/requests/q/simulation-result')).toBe('operator1')
    expect(resolveRole('/fe-api/product', 'POST', '/api/products/simulation-source-packages/p/sources/x/preview')).toBe('operator1')
  })

  it('simulation administration is admin-only; ground and acquisition steps are operator1', () => {
    expect(resolveRole('/fe-api/simulator', 'POST', '/api/simulation/scenarios')).toBe('admin')
    expect(resolveRole('/fe-api/simulator', 'POST', '/api/simulation/scenarios/s/advance')).toBe('admin')
    expect(resolveRole('/fe-api/simulator', 'POST', '/api/simulation/scenarios/s/loads/l/link')).toBe('admin')
    expect(resolveRole('/fe-api/simulator', 'GET', '/api/simulation/scenarios/s')).toBe('admin')
    expect(resolveRole('/fe-api/mission', 'POST', '/api/simulation-time-correlations')).toBe('admin')
    expect(resolveRole('/fe-api/mission', 'POST', '/api/authority-policies')).toBe('admin')
    expect(resolveRole('/fe-api/ground', 'POST', '/api/bookings')).toBe('operator1')
    expect(resolveRole('/fe-api/ground', 'POST', '/api/bookings/b/simulation-downlink-plan')).toBe('operator1')
    expect(resolveRole('/fe-api/ground', 'POST', '/api/stations')).toBe('admin')
    expect(resolveRole('/fe-api/acquisition', 'POST', '/api/acquisition/simulation-manifests')).toBe('operator1')
  })
})
