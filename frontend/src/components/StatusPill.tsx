import { cn } from '@/lib/cn';

export type StatusPillVariant = 'live' | 'upcoming' | 'sold-out';

type StatusPillProps = {
  variant: StatusPillVariant;
  children?: React.ReactNode;
  className?: string;
};

const LABELS: Record<StatusPillVariant, string> = {
  live: 'LIVE',
  upcoming: 'UPCOMING',
  'sold-out': 'SOLD OUT',
};

const STYLES: Record<StatusPillVariant, string> = {
  live: 'bg-accent text-fg-inverse',
  upcoming: 'bg-surface-2 text-fg-secondary',
  'sold-out': 'bg-surface-2 text-fg-tertiary',
};

export function StatusPill({ variant, children, className }: StatusPillProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-2 rounded-pill px-3 py-1',
        'text-caption font-medium uppercase tracking-[0.08em]',
        STYLES[variant],
        className,
      )}
    >
      {variant === 'live' && (
        <span
          aria-hidden
          className="h-1.5 w-1.5 rounded-pill bg-fg-inverse animate-dot-pulse"
        />
      )}
      {children ?? LABELS[variant]}
    </span>
  );
}
