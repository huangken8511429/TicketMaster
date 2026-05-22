import { useMemo } from 'react';
import { useCountdown } from '@/hooks/useCountdown';
import { cn } from '@/lib/cn';
import { StatusPill } from './StatusPill';

type SalesCountdownProps = {
  /** ISO datetime string or null = "already on sale". */
  salesStartAt: string | null | undefined;
  size?: 'compact' | 'hero';
  onElapsed?: () => void;
  className?: string;
};

function pad2(n: number): string {
  return n.toString().padStart(2, '0');
}

export function SalesCountdown({
  salesStartAt,
  size = 'compact',
  onElapsed,
  className,
}: SalesCountdownProps) {
  const target = useMemo(() => {
    if (!salesStartAt) return null;
    const t = new Date(salesStartAt).getTime();
    return Number.isFinite(t) ? t : null;
  }, [salesStartAt]);

  const cd = useCountdown(target, onElapsed);

  // If no target or already past, show LIVE chip directly.
  if (target === null || cd.expired) {
    return <StatusPill variant="live" className={className} />;
  }

  if (size === 'hero') {
    const cells: Array<{ label: string; value: string }> = [
      { label: '天', value: pad2(cd.days) },
      { label: '時', value: pad2(cd.hours) },
      { label: '分', value: pad2(cd.minutes) },
      { label: '秒', value: pad2(cd.seconds) },
    ];
    return (
      <div
        role="timer"
        aria-live="polite"
        className={cn('flex items-end gap-5 font-mono tabular', className)}
      >
        {cells.map(({ label, value }, i) => (
          <div key={label} className="flex items-end gap-2">
            <div className="flex flex-col items-start">
              <span className="text-[64px] leading-none font-bold text-accent transition-[transform] duration-base ease-snap">
                {value}
              </span>
              <span className="mt-2 text-caption uppercase tracking-[0.12em] text-fg-secondary">
                {label}
              </span>
            </div>
            {i < cells.length - 1 && (
              <span aria-hidden className="pb-9 text-heading-lg text-fg-tertiary font-normal">
                :
              </span>
            )}
          </div>
        ))}
      </div>
    );
  }

  return (
    <div
      role="timer"
      aria-live="polite"
      className={cn(
        'inline-flex items-baseline gap-1 font-mono tabular text-heading-md text-accent',
        className,
      )}
    >
      <span>{pad2(cd.hours + cd.days * 24)}</span>
      <span className="text-fg-secondary">:</span>
      <span>{pad2(cd.minutes)}</span>
      <span className="text-fg-secondary">:</span>
      <span>{pad2(cd.seconds)}</span>
    </div>
  );
}
