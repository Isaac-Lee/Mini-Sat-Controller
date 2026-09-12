export interface MissionInstant {
  seconds: number
  nanos: number
  scale: 'TAI' | 'UTC'
}

export interface MeanElements {
  noradId: number
  name: string
  internationalDesignator: string
  epochUtc: string
  meanMotionRevolutionsPerDay: number
  eccentricity: number
  inclinationDegrees: number
  ascendingNodeDegrees: number
  pericenterDegrees: number
  meanAnomalyDegrees: number
  bstar: number
  elementSetNumber: number
  revolutionNumber: number
  classification: string
}

export interface OrbitSnapshot {
  id: string
  elements: MeanElements
  provider: string
  sourceUrl: string
  fetchedAt: MissionInstant
  rawSha256: string
}

export interface TrackedSatelliteRow {
  norad_id: number
  display_name: string
  enabled: boolean
  next_attempt_at: string | null
  last_snapshot_id: string | null
  last_error: string | null
  fetched_at: string | null
}

export interface PredictionManifest {
  id: string
  solutionId: string
  spacecraftId: string
  model: string
  frame: string
  coverage: { start: MissionInstant; end: MissionInstant }
  stepSeconds: number
  sampleCount: number
  referenceDigest: string
  objectReference: string
}

export interface GroundPoint {
  time: MissionInstant
  latitudeDegrees: number
  longitudeDegrees: number
  altitudeMeters: number
  referenceDigest: string
}

export interface EphemerisDocument {
  prediction: {
    solutionId: string
    model: string
    frame: string
    coverage: { start: MissionInstant; end: MissionInstant }
    stepSeconds: number
    samples: Array<{
      time: MissionInstant
      positionMeters: { x: number; y: number; z: number }
      velocityMetersPerSecond: { x: number; y: number; z: number }
    }>
  }
  groundTrack: GroundPoint[]
  referenceDigest: string
}

// ---- Tasking (request-ui) ----

export interface Area {
  id: string
  west: number
  south: number
  east: number
  north: number
  sourceReference: string
}

export interface Criteria {
  minimumCoverageFraction: number
  maximumCloudFraction: number
}

export type InteractionPreference = 'AUTO' | 'CONFIRM'

export type RequestStatus =
  | 'RECEIVED'
  | 'CLARIFICATION_NEEDED'
  | 'ACCEPTED'
  | 'SCHEDULED'
  | 'PARTIALLY_FULFILLED'
  | 'FULFILLED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED'

export interface ObservationRequest {
  id: { value: string }
  aoiId: { value: string } | null
  revision: number
  effectiveCriteriaJson: string
  deadline: MissionInstant | null
  requestedPriority: number
  preference: InteractionPreference
  status: RequestStatus
}

export interface RequestDetails {
  request: ObservationRequest
  owner: string
  target: string
  area: Area | null
  criteria: Criteria
  createdAt: MissionInstant
  updatedAt: MissionInstant
  reason: string
  clarificationOptions: Area[]
}

export interface RequestState {
  id: string
  version: number
  body: RequestDetails
}

export interface RequestPage {
  items: RequestState[]
  nextCursor: string | null
}

export interface Submission {
  target: string
  area: Area | null
  criteria: Criteria | null
  deadline: MissionInstant | null
  priority: number
  preference: InteractionPreference
}

// ---- Monitoring (monitor-ui) ----

export type TelemetryEnvironment = 'SIMULATION' | 'HARDWARE'
export type SpacecraftMode = 'NOMINAL' | 'SAFE' | 'UNKNOWN'
export type Confidence = 'UNKNOWN' | 'FRESH' | 'DEGRADED' | 'STALE'
export type Quality = 'GOOD' | 'BAD'
export type Disposition = 'ACCEPTED' | 'OUT_OF_ORDER' | 'BAD_QUALITY' | 'FUTURE_TIMESTAMP' | 'SUPERSEDED_BINDING'

export interface TelemetryBinding {
  spacecraftId: string
  version: number
  source: string
  environment: TelemetryEnvironment
  maximumAgeSeconds: number
  futureSkewSeconds: number
  approvalReference: string
}

export interface TelemetryFrame {
  id: string
  spacecraftId: string
  bindingVersion: number
  source: string
  sequence: number
  observedAt: MissionInstant
  quality: Quality
  mode: SpacecraftMode
  batteryWh: number
  storageMb: number
  propellantKg: number
  provenance: string
}

export interface TelemetryReceipt {
  frame: TelemetryFrame
  receivedAt: MissionInstant
  disposition: Disposition
}

export interface Estimate {
  binding: TelemetryBinding
  accepted: TelemetryReceipt | null
  newestEvidence: TelemetryReceipt | null
}

export interface EstimateView {
  estimate: { id: string; version: number; body: Estimate }
  evaluatedAt: MissionInstant
  confidence: Confidence
}

// ---- Anomaly / safety (authority-ui) ----

export type SafetyReason =
  | 'OPERATOR_ENABLE_REQUIRED'
  | 'BINDING_MISMATCH'
  | 'MONITORING_UNAVAILABLE'
  | 'UNKNOWN_STATE'
  | 'STALE_STATE'
  | 'DEGRADED_STATE'
  | 'SAFE_MODE'
  | 'LOW_BATTERY'
  | 'STORAGE_LIMIT'
  | 'LOW_PROPELLANT'

export interface SafetyApproval {
  actor: string
  decisionReference: string
  grantedAt: MissionInstant
  validUntil: MissionInstant
  generation: number
}

export interface SafetyLatch {
  spacecraftId: string
  policyVersion: number
  generation: number
  frozen: boolean
  reasons: SafetyReason[]
  approvals: SafetyApproval[]
}

export interface SafetyLatchState {
  id: string
  version: number
  body: SafetyLatch
}

export interface SafetyPolicy {
  spacecraftId: string
  version: number
  telemetryBindingVersion: number
  minimumBatteryWh: number
  maximumStorageMb: number
  minimumPropellantKg: number
  recoveryApprovals: number
  approvalValiditySeconds: number
  approvalReference: string
}

export interface SafetyCheck {
  clear: boolean
  safetyVersion: number
  policyVersion: number
  monitoringVersion: number
  evaluatedAt: MissionInstant
  currentReasons: SafetyReason[]
  latch: SafetyLatch
}

export interface RecoveryResult {
  approved: boolean
  check?: SafetyCheck
  state?: SafetyLatchState
}

export interface AnomalyIncident {
  anomaly: {
    id: { value: string }
    spacecraftId: { value: string }
    severity: 'CRITICAL'
    declaredAt: MissionInstant
    evidenceReference: string
  }
  policyVersion: number
  generation: number
  reasons: SafetyReason[]
}

export interface AnomalyState {
  id: string
  version: number
  body: AnomalyIncident
}

export interface IncidentView {
  incident: AnomalyIncident
  resolution: unknown | null
}

// ---- Access predictions (timeline-ui) ----

export type AccessKind = 'GROUND_CONTACT' | 'POINT_IMAGING'

export interface AccessTarget {
  id: string
  latitudeDegrees: number
  longitudeDegrees: number
  altitudeMeters: number
}

export interface TimeWindow {
  start: MissionInstant
  end: MissionInstant
}

export interface AccessQuery {
  kind: AccessKind
  target: AccessTarget
  horizon: TimeWindow
  minimumElevationDegrees: number
  maximumOffNadirDegrees: number
  minimumDurationSeconds: number
}

export interface AccessPrediction {
  solutionId: string
  spacecraftId: string
  model: string
  referenceDigest: string
  query: AccessQuery
  rootToleranceSeconds: number
  maximumCheckSeconds: number
  windows: TimeWindow[]
}
