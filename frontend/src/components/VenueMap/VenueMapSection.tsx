import { useState, useCallback, type KeyboardEvent, type PointerEvent } from 'react';
import type {
  SectionAvailability,
  SectionStatus,
  StagePosition,
  VenueSeatMapSection,
} from '@/api/types';

const STATUS_LABEL: Record<SectionStatus, string> = {
  NOT_STARTED: '即將開賣',
  ON_SALE_PLENTY: '熱賣中',
  ON_SALE_LIMITED: '即將售完',
  ON_SALE_FEW: '僅剩數張',
  SOLD_OUT: '已售完',
};

type StatusVisual = {
  fill: string;
  fillOpacity: number;
  hoverOpacity: number;
  stroke: string;
  strokeWidth: number;
  strokeDasharray?: string;
  label: string;
  pulse: boolean;
};

const STATUS_VISUAL: Record<SectionStatus, StatusVisual> = {
  NOT_STARTED: {
    fill: 'var(--bg-surface-2)',
    fillOpacity: 0.6,
    hoverOpacity: 0.6,
    stroke: 'var(--line-subtle)',
    strokeWidth: 1,
    label: 'var(--fg-tertiary)',
    pulse: false,
  },
  ON_SALE_PLENTY: {
    fill: 'var(--status-plenty)',
    fillOpacity: 0.3,
    hoverOpacity: 0.5,
    stroke: 'var(--status-plenty)',
    strokeWidth: 1.5,
    label: 'var(--status-plenty)',
    pulse: false,
  },
  ON_SALE_LIMITED: {
    fill: 'var(--status-limited)',
    fillOpacity: 0.3,
    hoverOpacity: 0.5,
    stroke: 'var(--status-limited)',
    strokeWidth: 1.5,
    label: 'var(--status-limited)',
    pulse: false,
  },
  ON_SALE_FEW: {
    fill: 'var(--status-few)',
    fillOpacity: 0.3,
    hoverOpacity: 0.5,
    stroke: 'var(--status-few)',
    strokeWidth: 2,
    label: 'var(--status-few)',
    pulse: true,
  },
  SOLD_OUT: {
    fill: 'var(--bg-surface-2)',
    fillOpacity: 0.4,
    hoverOpacity: 0.4,
    stroke: 'var(--line-subtle)',
    strokeWidth: 1,
    strokeDasharray: '4 4',
    label: 'var(--fg-tertiary)',
    pulse: false,
  },
};

const UNCONFIGURED_VISUAL: StatusVisual = {
  fill: 'var(--bg-surface-2)',
  fillOpacity: 0.2,
  hoverOpacity: 0.2,
  stroke: 'var(--line-subtle)',
  strokeWidth: 1,
  strokeDasharray: '4 4',
  label: 'var(--fg-tertiary)',
  pulse: false,
};

const INTERACTIVE_STATUSES = new Set<SectionStatus>([
  'ON_SALE_PLENTY',
  'ON_SALE_LIMITED',
  'ON_SALE_FEW',
]);

function centroidOfPolygon(poly: Array<[number, number]>) {
  const n = poly.length;
  const sum = poly.reduce((acc, [x, y]) => ({ x: acc.x + x, y: acc.y + y }), { x: 0, y: 0 });
  return { x: sum.x / n, y: sum.y / n };
}

type BBox = { x: number; y: number; w: number; h: number };

function bboxOfSection(s: VenueSeatMapSection): BBox | null {
  if (s.shape === 'polygon' && s.polygon) {
    const xs = s.polygon.map((p) => p[0]);
    const ys = s.polygon.map((p) => p[1]);
    const minX = Math.min(...xs);
    const minY = Math.min(...ys);
    return { x: minX, y: minY, w: Math.max(...xs) - minX, h: Math.max(...ys) - minY };
  }
  if (s.shape === 'rect' && s.rect) {
    return { x: s.rect.x, y: s.rect.y, w: s.rect.width, h: s.rect.height };
  }
  if (s.shape === 'circle' && s.circle) {
    return { x: s.circle.cx - s.circle.r, y: s.circle.cy - s.circle.r, w: 2 * s.circle.r, h: 2 * s.circle.r };
  }
  return null;
}

/**
 * Place a small "STAGE" rect inside the section, on the edge that faces the
 * venue stage (per `section.stageFacing`). This gives each section a self-
 * contained spatial cue without relying on hover tooltips.
 *
 * Sizing: ~50% of the section's shorter dimension, capped at 70×14, with a
 * 6-unit inset from the edge so it doesn't visually collide with the polygon
 * stroke.
 */
function miniStageRect(bb: BBox, facing: StagePosition | undefined) {
  const stageW = Math.min(bb.w * 0.5, 70);
  const stageH = Math.min(bb.h * 0.18, 14);
  const inset = 6;
  switch (facing) {
    case 'south':
      return {
        x: bb.x + (bb.w - stageW) / 2,
        y: bb.y + bb.h - stageH - inset,
        width: stageW,
        height: stageH,
      };
    case 'east':
      return {
        x: bb.x + bb.w - stageW - inset,
        y: bb.y + (bb.h - stageH) / 2,
        width: stageW,
        height: stageH,
      };
    case 'west':
      return {
        x: bb.x + inset,
        y: bb.y + (bb.h - stageH) / 2,
        width: stageW,
        height: stageH,
      };
    case 'north':
    case 'center':
    default:
      return {
        x: bb.x + (bb.w - stageW) / 2,
        y: bb.y + inset,
        width: stageW,
        height: stageH,
      };
  }
}

function deriveShapeGeometry(s: VenueSeatMapSection) {
  if (s.shape === 'polygon' && s.polygon) {
    return {
      el: 'polygon' as const,
      points: s.polygon.map((p) => p.join(',')).join(' '),
      anchor: s.labelAnchor ?? centroidOfPolygon(s.polygon),
    };
  }
  if (s.shape === 'rect' && s.rect) {
    return {
      el: 'rect' as const,
      rect: s.rect,
      anchor: s.labelAnchor ?? { x: s.rect.x + s.rect.width / 2, y: s.rect.y + s.rect.height / 2 },
    };
  }
  if (s.shape === 'circle' && s.circle) {
    return {
      el: 'circle' as const,
      circle: s.circle,
      anchor: s.labelAnchor ?? { x: s.circle.cx, y: s.circle.cy },
    };
  }
  return null;
}

type Props = {
  section: VenueSeatMapSection;
  availability?: SectionAvailability;
  onPick: (section: SectionAvailability) => void;
};

export function VenueMapSection({ section, availability, onPick }: Props) {
  const visual = availability ? STATUS_VISUAL[availability.status] : UNCONFIGURED_VISUAL;
  const interactive = !!availability && INTERACTIVE_STATUSES.has(availability.status);
  const [hover, setHover] = useState(false);

  const geometry = deriveShapeGeometry(section);
  const bb = bboxOfSection(section);
  const stage = bb ? miniStageRect(bb, section.stageFacing) : null;
  // Shift the section name label off the stage rect so they don't visually collide
  // when the section is small. For north / center facing (label above) we nudge
  // the label down; for south we nudge it up; east/west we keep centroid-vertical.
  const labelAnchor = (() => {
    if (!geometry) return null;
    if (!stage) return geometry.anchor;
    const facing = section.stageFacing;
    const nudge = (stage.height + 6) / 2;
    if (facing === 'south') return { x: geometry.anchor.x, y: geometry.anchor.y - nudge };
    if (facing === 'east' || facing === 'west') return geometry.anchor;
    return { x: geometry.anchor.x, y: geometry.anchor.y + nudge };
  })();

  const handlePick = useCallback(() => {
    if (!interactive || !availability) return;
    onPick(availability);
  }, [interactive, availability, onPick]);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<SVGElement>) => {
      if (!interactive) return;
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        handlePick();
      }
    },
    [interactive, handlePick],
  );

  const handlePointerEnter = useCallback((_e: PointerEvent<SVGElement>) => {
    if (interactive) setHover(true);
  }, [interactive]);

  const handlePointerLeave = useCallback(() => setHover(false), []);

  if (!geometry) return null;

  const statusLabel = availability ? STATUS_LABEL[availability.status] : '未配置';
  const ariaLabel = `${section.displayName ?? section.name}：${statusLabel}`;

  const commonProps = {
    role: 'button' as const,
    tabIndex: interactive ? 0 : -1,
    'aria-label': ariaLabel,
    'aria-disabled': !interactive,
    onClick: interactive ? handlePick : undefined,
    onKeyDown: handleKeyDown,
    onPointerEnter: handlePointerEnter,
    onPointerLeave: handlePointerLeave,
    fill: visual.fill,
    fillOpacity: hover ? visual.hoverOpacity : visual.fillOpacity,
    stroke: visual.stroke,
    strokeWidth: visual.strokeWidth + (hover ? 0.5 : 0),
    strokeDasharray: visual.strokeDasharray,
    style: {
      cursor: interactive ? 'pointer' : 'not-allowed',
      transition: 'fill-opacity var(--motion-base) var(--ease-standard), stroke-width var(--motion-base) var(--ease-standard)',
      transformOrigin: `${geometry.anchor.x}px ${geometry.anchor.y}px`,
    } as const,
    className: visual.pulse ? 'animate-badge-pulse' : undefined,
  };

  return (
    <g>
      {geometry.el === 'polygon' && <polygon {...commonProps} points={geometry.points} />}
      {geometry.el === 'rect' && (
        <rect
          {...commonProps}
          x={geometry.rect.x}
          y={geometry.rect.y}
          width={geometry.rect.width}
          height={geometry.rect.height}
          rx={4}
        />
      )}
      {geometry.el === 'circle' && (
        <circle {...commonProps} cx={geometry.circle.cx} cy={geometry.circle.cy} r={geometry.circle.r} />
      )}

      {/* Mini STAGE marker — one per section, placed on the edge facing the venue stage. */}
      {stage && (
        <g aria-hidden pointerEvents="none">
          <rect
            x={stage.x}
            y={stage.y}
            width={stage.width}
            height={stage.height}
            fill="var(--bg-surface-2)"
            stroke="var(--line-strong)"
            strokeWidth={0.6}
            opacity={0.85}
            rx={2}
          />
          <text
            x={stage.x + stage.width / 2}
            y={stage.y + stage.height / 2}
            textAnchor="middle"
            dominantBaseline="central"
            fill="var(--fg-secondary)"
            style={{
              fontSize: Math.max(7, stage.height * 0.62),
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              fontFamily: 'var(--font-display)',
            }}
          >
            STAGE
          </text>
        </g>
      )}

      <text
        x={labelAnchor!.x}
        y={labelAnchor!.y}
        textAnchor="middle"
        dominantBaseline="central"
        fill={visual.label}
        pointerEvents="none"
        style={{
          fontSize: 16,
          fontWeight: 700,
          letterSpacing: '-0.01em',
          textDecoration: availability?.status === 'SOLD_OUT' ? 'line-through' : 'none',
        }}
      >
        {section.displayName ?? section.name}
      </text>
    </g>
  );
}
