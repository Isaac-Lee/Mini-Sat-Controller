import type { EphemerisDocument, GroundPoint } from './api/types'

// Synthetic, clearly-labeled demo track (a plain circular pass) — never derived from real telemetry.
function buildDemoGroundTrack(): GroundPoint[] {
  const points: GroundPoint[] = []
  const startSeconds = 1_800_000_000
  for (let i = 0; i <= 90; i++) {
    const t = i / 90
    const lat = 51 * Math.sin(t * Math.PI * 2)
    const lon = -180 + t * 360
    points.push({
      time: { seconds: startSeconds + i * 60, nanos: 0, scale: 'TAI' },
      latitudeDegrees: lat,
      longitudeDegrees: lon,
      altitudeMeters: 500_000,
      referenceDigest: 'demo',
    })
  }
  return points
}

export const demoGroundTrack = buildDemoGroundTrack()

export const demoDocument: EphemerisDocument = {
  prediction: {
    solutionId: 'demo-solution',
    model: 'DEMO-CIRCULAR (합성 데이터, 실제 궤도 아님)',
    frame: 'EME2000',
    coverage: {
      start: demoGroundTrack[0].time,
      end: demoGroundTrack[demoGroundTrack.length - 1].time,
    },
    stepSeconds: 60,
    samples: [],
  },
  groundTrack: demoGroundTrack,
  referenceDigest: 'demo',
}
