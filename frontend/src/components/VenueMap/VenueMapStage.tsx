import type { VenueSeatMap } from '@/api/types';

function centroidOfRect(rect: { x: number; y: number; width: number; height: number }) {
  return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
}

function centroidOfPolygon(poly: Array<[number, number]>) {
  const n = poly.length;
  const sum = poly.reduce((acc, [x, y]) => ({ x: acc.x + x, y: acc.y + y }), { x: 0, y: 0 });
  return { x: sum.x / n, y: sum.y / n };
}

export function VenueMapStage({ stage }: { stage: VenueSeatMap['stage'] }) {
  let label = { x: 0, y: 0 };
  return (
    <g aria-hidden>
      {stage.shape === 'rect' && stage.rect && (() => {
        label = centroidOfRect(stage.rect);
        return (
          <rect
            x={stage.rect.x}
            y={stage.rect.y}
            width={stage.rect.width}
            height={stage.rect.height}
            fill="var(--bg-surface-2)"
            stroke="var(--line-strong)"
            strokeWidth={1}
            rx={4}
          />
        );
      })()}
      {stage.shape === 'polygon' && stage.polygon && (() => {
        label = centroidOfPolygon(stage.polygon);
        return (
          <polygon
            points={stage.polygon.map((p) => p.join(',')).join(' ')}
            fill="var(--bg-surface-2)"
            stroke="var(--line-strong)"
            strokeWidth={1}
          />
        );
      })()}
      <text
        x={label.x}
        y={label.y}
        textAnchor="middle"
        dominantBaseline="central"
        fill="var(--fg-secondary)"
        style={{
          fontSize: 14,
          letterSpacing: '0.18em',
          textTransform: 'uppercase',
          fontFamily: 'var(--font-display)',
        }}
      >
        {stage.label}
      </text>
    </g>
  );
}
