import { useMemo } from 'react';
import { useCountdown } from '@/hooks/useCountdown';
import { cn } from '@/lib/cn';

type HoldCountdownProps = {
  /** Unix ms timestamp of booking completion. */
  startedAt: number;
  durationMs?: number;
  onExpired: () => void;
  className?: string;
};

const DEFAULT_DURATION = 5 * 60 * 1000;
const URGENT_THRESHOLD = 60 * 1000;

function pad2(n: number): string {
  return n.toString().padStart(2, '0');
}

export function HoldCountdown({
  startedAt,
  durationMs = DEFAULT_DURATION,
  onExpired,
  className,
}: HoldCountdownProps) {
  const target = useMemo(() => startedAt + durationMs, [startedAt, durationMs]);
  const cd = useCountdown(target, onExpired);

  const urgent = !cd.expired && cd.remainingMs <= URGENT_THRESHOLD;
  const totalSeconds = cd.minutes * 60 + cd.seconds;

  return (
    <div className={cn('flex flex-col items-start gap-2', className)} role="timer" aria-live="polite">
      <div
        className={cn(
          'font-mono tabular font-bold leading-none',
          'text-[clamp(3rem,7vw,6rem)]',
          cd.expired
            ? 'text-fg-tertiary'
            : urgent
              ? 'text-status-few animate-dot-pulse'
              : 'text-accent',
        )}
      >
        {pad2(Math.floor(totalSeconds / 60))}
        <span className="text-fg-secondary mx-2">:</span>
        {pad2(totalSeconds % 60)}
      </div>
      <p className="text-body-md text-fg-secondary">
        {cd.expired ? '保留時間已過' : '請於倒數時間內確認您的座位'}
      </p>
    </div>
  );
}
