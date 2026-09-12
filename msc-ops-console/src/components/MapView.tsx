import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect, useRef } from 'react'
import type { Area, GroundPoint } from '../api/types'

interface Props {
  track: GroundPoint[]
  currentIndex: number | null
  requestArea?: Area | null
}

// Splits the polyline wherever the ground track crosses the +/-180 deg antimeridian,
// so Leaflet doesn't draw one long line straight across the map at the wrap point.
function splitAtAntimeridian(track: GroundPoint[]): [number, number][][] {
  const segments: [number, number][][] = []
  let current: [number, number][] = []
  for (let i = 0; i < track.length; i++) {
    const point = track[i]
    if (i > 0 && Math.abs(point.longitudeDegrees - track[i - 1].longitudeDegrees) > 180) {
      segments.push(current)
      current = []
    }
    current.push([point.latitudeDegrees, point.longitudeDegrees])
  }
  if (current.length) segments.push(current)
  return segments
}

export function MapView({ track, currentIndex, requestArea }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const trackLayerRef = useRef<L.LayerGroup | null>(null)
  const markerRef = useRef<L.CircleMarker | null>(null)
  const areaLayerRef = useRef<L.LayerGroup | null>(null)

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return
    const map = L.map(containerRef.current, { worldCopyJump: true }).setView([20, 130], 3)
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap contributors',
      maxZoom: 10,
    }).addTo(map)
    mapRef.current = map
    trackLayerRef.current = L.layerGroup().addTo(map)
    areaLayerRef.current = L.layerGroup().addTo(map)
    return () => {
      map.remove()
      mapRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const layer = trackLayerRef.current
    if (!map || !layer) return
    layer.clearLayers()
    if (track.length === 0) return
    for (const segment of splitAtAntimeridian(track)) {
      L.polyline(segment, { color: '#2563eb', weight: 2, opacity: 0.8 }).addTo(layer)
    }
  }, [track])

  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    if (currentIndex === null || !track[currentIndex]) {
      markerRef.current?.remove()
      markerRef.current = null
      return
    }
    const point = track[currentIndex]
    if (!markerRef.current) {
      markerRef.current = L.circleMarker([point.latitudeDegrees, point.longitudeDegrees], {
        radius: 7,
        color: '#dc2626',
        fillColor: '#ef4444',
        fillOpacity: 1,
      }).addTo(map)
    } else {
      markerRef.current.setLatLng([point.latitudeDegrees, point.longitudeDegrees])
    }
  }, [track, currentIndex])

  useEffect(() => {
    const map = mapRef.current
    const layer = areaLayerRef.current
    if (!map || !layer) return
    layer.clearLayers()
    if (!requestArea) return
    // WGS84 rectangle, non-antimeridian-crossing by contract (msc-contracts Area).
    L.rectangle(
      [
        [requestArea.south, requestArea.west],
        [requestArea.north, requestArea.east],
      ],
      { color: '#16a34a', weight: 2, fillOpacity: 0.08 },
    )
      .bindTooltip(`요청 영역 · 출처: ${requestArea.sourceReference}`, { permanent: false })
      .addTo(layer)
  }, [requestArea])

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%' }}>
      <div ref={containerRef} role="img" aria-label="위성 지상궤적 및 요청 영역 지도" style={{ width: '100%', height: '100%' }} />
      {requestArea && (
        <div className="map-legend">
          요청 영역(초록 사각형) · 출처: {requestArea.sourceReference} · 센서 투영 오버레이는 이 지도에 미연결 (샘플 평가는 임무 흐름에서 조회)
        </div>
      )}
      {requestArea && (
        <p className="sr-only">
          지도 텍스트 대체: 요청 영역 WGS84 경계 — 서쪽 {requestArea.west}°, 남쪽 {requestArea.south}°, 동쪽{' '}
          {requestArea.east}°, 북쪽 {requestArea.north}° (출처: {requestArea.sourceReference})
        </p>
      )}
    </div>
  )
}
