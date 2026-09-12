import { request } from './client'
import type { EstimateView } from './types'

const MONITORING = '/fe-api/monitoring'

export const monitoringApi = {
  currentEstimate: (spacecraftId: string) =>
    request<EstimateView>(`${MONITORING}/api/spacecraft-estimates/${encodeURIComponent(spacecraftId)}`),
}
