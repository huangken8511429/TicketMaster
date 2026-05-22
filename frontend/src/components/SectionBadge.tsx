import { cn } from '@/lib/cn';
import type { SectionStatus } from '@/api/types';

type SectionBadgeProps = {
  section: string;
  status: SectionStatus;
  onClick?: () => void;
  className?: string;
};

const STATUS_LABEL: Record<SectionStatus, string> = {
  NOT_STARTED: '即將開賣',
  ON_SALE_PLENTY: '熱賣中',
  ON_SALE_LIMITED: '即將售完',
  ON_SALE_FEW: '僅剩數張',
  SOLD_OUT: '已售完',
};

const STATUS_GLYPH: Record<SectionStatus, string> = {
  NOT_STARTED: '○',
  ON_SALE_PLENTY: '●',
  ON_SALE_LIMITED: '◐',
  ON_SALE_FEW: '▲',
  SOLD_OUT: '○',
};

const STATUS_STYLES: Record<SectionStatus, string> = {
  NOT_STARTED: 'bg-surface-2 text-fg-tertiary border-line-subtle cursor-not-allowed',
  ON_SALE_PLENTY: 'bg-surface text-status-plenty border-status-plenty hover:scale-[1.02]',
  ON_SALE_LIMITED: 'bg-surface text-status-limited border-status-limited hover:scale-[1.02]',
  ON_SALE_FEW: 'bg-surface text-status-few border-status-few animate-badge-pulse hover:scale-[1.02]',
  SOLD_OUT: 'bg-surface-2 text-fg-tertiary border-line-subtle cursor-not-allowed opacity-70',
};

export function SectionBadge({ section, status, onClick, className }: SectionBadgeProps) {
  const interactive = status === 'ON_SALE_PLENTY' || status === 'ON_SALE_LIMITED' || status === 'ON_SALE_FEW';

  return (
    <button
      type="button"
      onClick={interactive ? onClick : undefined}
      disabled={!interactive}
      aria-label={`區域 ${section}：${STATUS_LABEL[status]}`}
      className={cn(
        'group flex flex-col items-start gap-2',
        'rounded-sm border px-4 py-3',
        'transition-[border-color,color,transform] duration-slower ease-standard',
        'text-left',
        STATUS_STYLES[status],
        className,
      )}
    >
      <span className="flex items-center gap-2">
        <span aria-hidden className="text-body-sm leading-none">
          {STATUS_GLYPH[status]}
        </span>
        <span className="text-heading-md font-bold tracking-tight">{section}</span>
      </span>
      <small className="text-caption uppercase tracking-[0.06em] text-current opacity-90">
        {STATUS_LABEL[status]}
      </small>
    </button>
  );
}
