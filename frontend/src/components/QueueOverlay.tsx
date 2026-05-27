import { cn } from '@/lib/cn';
import { Button } from './Button';

type QueueOverlayProps = {
  bookingId: string;
  elapsedSec: number;
  state: 'queueing' | 'long-wait' | 'failed';
  onRetry?: () => void;
  onBackToList?: () => void;
};

export function QueueOverlay({ bookingId, elapsedSec, state, onRetry, onBackToList }: QueueOverlayProps) {
  const failed = state === 'failed';
  const subline =
    failed
      ? '您可以再試一次'
      : state === 'long-wait' || elapsedSec > 30
        ? '處理時間較長，請耐心等候'
        : '預估等待時間：約 10 秒';

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="排隊中"
      className={cn(
        'fixed inset-0 z-queue-overlay',
        'flex flex-col items-center justify-center gap-8 px-6',
        'bg-ink queue-bg-grid',
      )}
    >
      <RingPulse stopped={failed} />

      <div className="flex flex-col items-center gap-3 text-center max-w-xl animate-fade-up">
        <h2 className="text-display-md font-extrabold tracking-tight">
          {failed ? '很抱歉，這次沒搶到' : '正在為您處理...'}
        </h2>
        <p className="text-body-lg text-fg-secondary">{subline}</p>
        {!failed && (
          <p className="font-mono text-caption uppercase tracking-[0.16em] text-fg-tertiary">
            booking · {bookingId.slice(0, 8)} · {elapsedSec}s
          </p>
        )}
      </div>

      {failed && (
        <div className="flex gap-4 animate-fade-up">
          <Button variant="primary" onClick={onRetry}>
            回活動詳情
          </Button>
          <Button variant="secondary" onClick={onBackToList}>
            回活動列表
          </Button>
        </div>
      )}
    </div>
  );
}

function RingPulse({ stopped }: { stopped: boolean }) {
  const rings = [
    { r: 80, opacity: 0.6, delay: '0ms' },
    { r: 140, opacity: 0.4, delay: '-800ms' },
    { r: 200, opacity: 0.2, delay: '-1600ms' },
  ];
  return (
    <div className="relative h-[440px] w-[440px]" aria-hidden>
      <svg viewBox="-220 -220 440 440" className="absolute inset-0 h-full w-full">
        {rings.map(({ r, opacity, delay }) => (
          <circle
            key={r}
            cx={0}
            cy={0}
            r={r}
            fill="none"
            stroke={stopped ? '#3D3D42' : 'var(--accent)'}
            strokeWidth="1"
            opacity={opacity}
            style={
              stopped
                ? undefined
                : {
                    transformOrigin: 'center',
                    animation: 'queue-ring 2.4s cubic-bezier(0.65,0,0.35,1) infinite',
                    animationDelay: delay,
                  }
            }
          />
        ))}
        <circle cx={0} cy={0} r={4} fill={stopped ? '#3D3D42' : 'var(--accent)'} />
      </svg>
    </div>
  );
}
