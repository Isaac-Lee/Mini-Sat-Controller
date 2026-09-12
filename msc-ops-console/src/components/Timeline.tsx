interface Props {
  times: Date[]
  index: number
  onIndexChange: (index: number) => void
  playing: boolean
  onTogglePlay: () => void
  disabled: boolean
}

export function Timeline({ times, index, onIndexChange, playing, onTogglePlay, disabled }: Props) {
  const current = times[index]
  return (
    <div className="timeline">
      <button onClick={onTogglePlay} disabled={disabled || times.length === 0} className="play-button">
        {playing ? '일시정지' : '재생'}
      </button>
      <input
        type="range"
        min={0}
        max={Math.max(times.length - 1, 0)}
        value={index}
        disabled={disabled || times.length === 0}
        onChange={(e) => onIndexChange(Number(e.target.value))}
        style={{ flex: 1 }}
      />
      <span className="timeline-clock">
        {current ? (
          <>
            {current.toISOString().replace('.000Z', 'Z').replace('T', ' ')} UTC
            <span className="hint"> ({current.toLocaleString()})</span>
          </>
        ) : (
          '--'
        )}
      </span>
    </div>
  )
}
