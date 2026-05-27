import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { EventResponse } from '@/api/types';
import { SalesCountdown } from '@/components/SalesCountdown';
import { StatusPill } from '@/components/StatusPill';
import { cn } from '@/lib/cn';

type EventCardProps = {
  event: EventResponse;
  /** 0-based index, used as a tie-breaker for editorial palette variation. */
  index?: number;
  className?: string;
};

/**
 * Editorial poster card for the event list screen.
 *
 * Visual recipe (component-spec §7):
 *   - 2:3 aspect poster well (HSL gradient placeholder until backend supplies posterUrl)
 *   - Body: name (heading-lg, 2-line clamp), performer · venue, event date, sales status
 *   - Hover: subtle scale + acid-lime border + name turns accent
 *   - focus-visible: accent outline (delegated to global *:focus-visible CSS rule)
 *
 * Sales state ladder (drives bottom-right indicator):
 *   - salesStartAt in the future → <SalesCountdown size="compact" /> + UPCOMING pill
 *   - salesStartAt null / in the past → LIVE pill
 *   - countdown reaches 0 while on screen → flip to LIVE pill via local state (no refresh)
 *
 * `index` is just used to vary the HSL ramp so adjacent cards don't look identical
 * when the backend hasn't supplied posterUrl yet — purely cosmetic.
 */
export function EventCard({ event, index = 0, className }: EventCardProps) {
  const initiallyLive =
    !event.salesStartAt || new Date(event.salesStartAt).getTime() <= Date.now();
  const [isLive, setIsLive] = useState(initiallyLive);

  const hueSeed = event.id * 67 + index * 31;
  const eventDate = new Date(event.eventStartTime);
  const dateLabel = eventDate.toLocaleDateString('zh-TW', {
    month: 'short',
    day: 'numeric',
  });

  return (
    <Link
      to={`/events/${event.id}`}
      aria-label={`${event.name} — ${event.performerName}，${event.venueName}`}
      data-testid={`event-card-${event.id}`}
      className={cn(
        'group flex flex-col bg-surface border border-line-subtle rounded-sm overflow-hidden',
        'transition-[border-color,transform,box-shadow] duration-slower ease-standard',
        'hover:border-accent hover:scale-[1.02] hover:shadow-sm',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
        className,
      )}
    >
      {/* Poster well — 2:3 aspect, gradient placeholder + editorial markers */}
      <div
        className="relative aspect-[2/3] w-full"
        style={{
          background: `linear-gradient(150deg, hsl(${hueSeed % 360} 60% 12%), hsl(${(hueSeed + 80) % 360} 55% 22%))`,
        }}
        aria-hidden
      >
        <span className="absolute top-4 left-4 h-0.5 w-10 bg-accent" />
        <span className="absolute top-4 right-4 text-caption uppercase tracking-[0.16em] text-fg-primary/70 font-mono">
          EVT/{String(event.id).padStart(4, '0')}
        </span>
        <span
          className={cn(
            'absolute bottom-4 left-4 text-caption uppercase tracking-[0.14em] font-mono',
            'text-fg-primary/60 line-clamp-1 max-w-[80%]',
          )}
        >
          {event.performerName}
        </span>
      </div>

      {/* Body */}
      <div className="p-5 flex flex-col gap-3 flex-1">
        <div className="flex items-center justify-between gap-3">
          {isLive ? <StatusPill variant="live" /> : <StatusPill variant="upcoming" />}
          <span className="text-caption uppercase tracking-[0.1em] text-fg-tertiary font-mono">
            {dateLabel}
          </span>
        </div>

        <h3
          className={cn(
            'text-heading-lg font-extrabold tracking-tight line-clamp-2',
            'transition-colors duration-base ease-standard',
            'group-hover:text-accent',
          )}
        >
          {event.name}
        </h3>

        <p className="text-body-sm text-fg-secondary line-clamp-1">
          {event.performerName} · {event.venueName}
        </p>

        <div className="pt-2 mt-auto">
          <SalesCountdown
            salesStartAt={event.salesStartAt}
            size="compact"
            onElapsed={() => setIsLive(true)}
          />
        </div>
      </div>
    </Link>
  );
}
