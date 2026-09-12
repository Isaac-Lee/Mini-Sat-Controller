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
})
