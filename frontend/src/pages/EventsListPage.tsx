import { useMemo } from 'react';
import { useEvents } from '@/api/events';
import { Button } from '@/components/Button';
import { EventCard } from '@/components/EventCard';
import { cn } from '@/lib/cn';

/**
 * Screen 1 — Event list.
 *
 * Reference: specs/frontend-mvp/activity-flow.md §2, component-spec.md §7.
 * Responsive grid: 1 col mobile → 2 cols md → 3 cols lg → 4 cols xl.
 * Layout decisions:
 *   - Editorial hero with display-xl headline and a thin acid-lime ruler
 *   - 6-card skeleton on load (animate-pulse, 2:3 to mirror real cards)
 *   - Inline error block with retry CTA (Phase 4 style)
 *   - Empty state with editorial typography + secondary muted line
 *   - Card body delegates to <EventCard>, which owns its own sales-state lifecycle
 *     (compact SalesCountdown auto-flips to LIVE pill via onElapsed)
 */
export function EventsListPage() {
  const { data, isLoading, error, refetch, isFetching } = useEvents();

  const sortedEvents = useMemo(() => {
    if (!data) return [];
    // Editorial ordering: live first (closer to now), then upcoming chronologically.
    const now = Date.now();
    return [...data].sort((a, b) => {
      const aLive = !a.salesStartAt || new Date(a.salesStartAt).getTime() <= now;
      const bLive = !b.salesStartAt || new Date(b.salesStartAt).getTime() <= now;
      if (aLive !== bLive) return aLive ? -1 : 1;
      return new Date(a.eventStartTime).getTime() - new Date(b.eventStartTime).getTime();
    });
  }, [data]);

  return (
    <section className="mx-auto max-w-7xl px-6 py-10 md:py-16 flex flex-col gap-10 md:gap-12">
      <header className="flex flex-col gap-5">
        <div className="flex items-center gap-3">
          <span aria-hidden className="h-px w-12 bg-accent" />
          <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
            / Live + Upcoming
          </span>
        </div>
        <h1
          className={cn(
            'text-display-lg md:text-display-xl font-extrabold tracking-tight leading-[1.02]',
            'max-w-4xl',
          )}
        >
          現在能搶的，<span className="text-accent">就現在。</span>
        </h1>
        <p className="text-body-lg text-fg-secondary max-w-2xl">
          冷靜處理高併發。倒數結束的那一刻，你就在隊伍最前面。
        </p>
      </header>

      {isLoading && <SkeletonGrid />}

      {error && !isLoading && <ErrorBlock onRetry={() => refetch()} message={(error as Error).message} />}

      {!isLoading && !error && sortedEvents.length === 0 && <EmptyState />}

      {!isLoading && !error && sortedEvents.length > 0 && (
        <>
          <EventGrid>
            {sortedEvents.map((evt, i) => (
              <EventCard key={evt.id} event={evt} index={i} />
            ))}
          </EventGrid>
          {isFetching && (
            <p
              className="text-caption uppercase tracking-[0.14em] text-fg-tertiary font-mono"
              aria-live="polite"
            >
              / 更新中…
            </p>
          )}
        </>
      )}
    </section>
  );
}

function EventGrid({ children }: { children: React.ReactNode }) {
  return (
    <div
      role="list"
      aria-label="活動列表"
      className={cn(
        'grid gap-6 md:gap-6',
        'grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4',
      )}
    >
      {children}
    </div>
  );
}

function SkeletonGrid() {
  return (
    <div
      aria-busy="true"
      aria-label="活動列表載入中"
      className="grid gap-6 grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
    >
      {Array.from({ length: 6 }).map((_, i) => (
        <div
          key={i}
          className="flex flex-col bg-surface border border-line-subtle rounded-sm overflow-hidden"
        >
          <div className="aspect-[2/3] w-full bg-surface-2 animate-pulse" />
          <div className="p-5 flex flex-col gap-3">
            <div className="h-3 w-16 bg-surface-2 animate-pulse" />
            <div className="h-6 w-3/4 bg-surface-2 animate-pulse" />
            <div className="h-4 w-1/2 bg-surface-2 animate-pulse" />
            <div className="h-5 w-2/3 bg-surface-2 animate-pulse mt-2" />
          </div>
        </div>
      ))}
    </div>
  );
}

function ErrorBlock({ onRetry, message }: { onRetry: () => void; message: string }) {
  return (
    <div
      role="alert"
      className={cn(
        'border border-signal-error/40 bg-surface p-6 rounded-sm',
        'flex flex-col md:flex-row md:items-center md:justify-between gap-4',
      )}
    >
      <div className="flex flex-col gap-1">
        <p className="text-caption uppercase tracking-[0.14em] text-signal-error">/ Error</p>
        <p className="text-body-md text-fg-primary">載入失敗，請稍後再試</p>
        {message && <p className="text-body-sm text-fg-tertiary font-mono">{message}</p>}
      </div>
      <Button variant="secondary" size="sm" onClick={onRetry}>
        重試
      </Button>
    </div>
  );
}

function EmptyState() {
  return (
    <div
      role="status"
      className={cn(
        'border border-line-subtle bg-surface rounded-sm',
        'flex flex-col items-start gap-4 px-8 py-16 md:py-24',
      )}
    >
      <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">/ Empty</span>
      <h2 className="text-display-md font-extrabold tracking-tight">
        目前沒有<span className="text-accent">活動</span>。
      </h2>
      <p className="text-body-md text-fg-secondary max-w-xl">
        新場次釋出時，這裡會第一時間刷新。先去喝杯水。
      </p>
    </div>
  );
}
