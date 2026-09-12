import { request } from './client'
import type { MissionInstant } from './types'

export interface SimulationSource {
  receiptId: string
  payloadId: string
  byteCount: number
  sha256: string
  previewByteCount: number
  previewSha256: string
  contentPath: string
  previewPath: string
}

export interface SimulationResult {
  requestId: string
  requestRevision: number
  environment: string
  status: string
  completionBasis: string
  productId: string
  scenarioId: string
  imageLoadId: string
  downlinkLoadId: string
  reviewer: string
  reviewReference: string
  completedAt: MissionInstant
  sourceHashes: Record<string, string>
  sources: SimulationSource[]
}

// Request-owned API keeps the requester ownership check for metadata and bytes alike.
const resultPath = (requestId: string) =>
  `/fe-api/tasking/api/requests/${encodeURIComponent(requestId)}/simulation-result`

export const productsApi = {
  result: (requestId: string) =>
    request<{ id: string; version: number; body: SimulationResult }>(resultPath(requestId)),
  sourceUrl: (requestId: string, receiptId: string, preview = false) =>
    `${resultPath(requestId)}/sources/${encodeURIComponent(receiptId)}/${preview ? 'preview' : 'content'}`,
}
