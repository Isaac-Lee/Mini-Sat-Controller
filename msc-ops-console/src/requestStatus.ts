import type { RequestStatus } from './api/types'

export const STATUS_LABEL: Record<RequestStatus, string> = {
  RECEIVED: '접수됨 (해석 대기 — RabbitMQ/reference-data 비동기 처리 중, 오류 아님)',
  CLARIFICATION_NEEDED: '명확화 필요',
  ACCEPTED: '수락됨 (계획 대상)',
  SCHEDULED: '스케줄 확정',
  PARTIALLY_FULFILLED: '부분 충족',
  FULFILLED: '충족 완료 (합성 V1 여부는 결과 근거 확인)',
  REJECTED: '거부됨',
  EXPIRED: '기한 만료',
  CANCELLED: '취소됨',
}

export const STATUS_CLASS: Record<RequestStatus, string> = {
  RECEIVED: 'status-warn',
  CLARIFICATION_NEEDED: 'status-warn',
  ACCEPTED: 'status-ok',
  SCHEDULED: 'status-ok',
  PARTIALLY_FULFILLED: 'status-warn',
  FULFILLED: 'status-ok',
  REJECTED: 'status-error',
  EXPIRED: 'status-error',
  CANCELLED: 'status-error',
}
