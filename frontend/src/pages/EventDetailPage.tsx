import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import { useCreateBooking } from '@/api/bookings';
import { useEventDetail } from '@/api/events';
import { sectionsKeys, useSections } from '@/api/sections';
import { useParsedVenueSeatMap } from '@/api/venues';
import type { SectionAvailability } from '@/api/types';
import { BookingConfirmModal } from '@/components/BookingConfirmModal';
import { Button } from '@/components/Button';
import { SalesCountdown } from '@/components/SalesCountdown';
import { SectionList } from '@/components/SectionList';
import { SeatLevelPlaceholder } from '@/components/SeatLevelPlaceholder';
import { StatusPill } from '@/components/StatusPill';
import { VenueMap } from '@/components/VenueMap';
import { useAnonymousUserId } from '@/hooks/useAnonymousUserId';
import { useSectionStatusStream } from '@/hooks/useSectionStatusStream';
import { useToast } from '@/hooks/useToast';
import { cn } from '@/lib/cn';

const INTERACTIVE_STATUSES = new Set<SectionAvailability['status']>([
  'ON_SALE_PLENTY',
  'ON_SALE_LIMITED',
  'ON_SALE_FEW',
]);

export function EventDetailPage() {
  const { id } = useParams<{ id: string }>();
  const eventId = id ?? '';
  const numericEventId = Number(eventId);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const userId = useAnonymousUserId();
  const { push: pushToast } = useToast();

  const eventQuery = useEventDetail(eventId || undefined);
  const sectionsQuery = useSections(eventId || undefined);
  const event = eventQuery.data;
  const parsedSeatMap = useParsedVenueSeatMap(event?.venueId);
  /**
   * SSE write-through: the stream merges into the same React Query cache key
   * (`sectionsKeys.byEvent`) so `sectionsQuery.data` is the single source of truth
   * for badge rendering. We do not read `stream.sections` directly to avoid
   * dual-source-of-truth drift.
   */
  const stream = useSectionStatusStream(eventId || undefined);

  const [selectedSection, setSelectedSection] = useState<SectionAvailability | null>(null);
  const createBooking = useCreateBooking();

  const sections = sectionsQuery.data ?? [];
  const bookingMode = event?.bookingMode ?? 'SECTION_TEXT';
  const seatMapInvalid = bookingMode === 'SECTION_VISUAL' && !!event && !parsedSeatMap;

  useEffect(() => {
    if (seatMapInvalid && event) {
      console.warn(
        `[VenueMap] Event ${event.id} (${event.name}) is SECTION_VISUAL but venue ${event.venueId} has no valid seatMap — falling back to <SectionList>.`,
      );
    }
  }, [seatMapInvalid, event]);

  const allSoldOut = useMemo(
    () => sections.length > 0 && sections.every((s) => s.status === 'SOLD_OUT'),
    [sections],
  );

  const salesNotStarted = useMemo(() => {
    if (!event?.salesStartAt) return false;
    return new Date(event.salesStartAt).getTime() > Date.now();
  }, [event?.salesStartAt]);

  if (!eventId) {
    return <ErrorState title="活動不存在" detail="缺少活動 ID" />;
  }

  if (eventQuery.isLoading) {
    return <EventDetailSkeleton />;
  }

  if (eventQuery.error) {
    const err = eventQuery.error as Error;
    const status = err instanceof ApiError ? err.status : undefined;
    if (status === 404) {
      return <ErrorState title="活動不存在" detail="這場活動可能已下架或網址錯誤" />;
    }
    return <ErrorState title="載入失敗" detail={err.message} onRetry={() => eventQuery.refetch()} />;
  }

  if (!event) {
    return <ErrorState title="活動不存在" />;
  }

  const handleBadgeClick = (section: SectionAvailability) => {
    if (!INTERACTIVE_STATUSES.has(section.status)) return;
    setSelectedSection(section);
  };

  const handleConfirmBooking = async (seatCount: number) => {
    if (!selectedSection) return;
    try {
      const accepted = await createBooking.mutateAsync({
        eventId: numericEventId,
        section: selectedSection.section,
        seatCount,
        userId,
      });
      const sectionSnapshot = selectedSection;
      setSelectedSection(null);
      // Phase 7: forward `basePrice` (+ section/seatCount snapshot) through router
      // state so the queue → confirm hop can render the total price without
      // a redundant fetch. `fromEventId` lets QueuePage's failed-state CTA
      // return precisely to this event detail page.
      navigate(`/queue/${accepted.bookingId}`, {
        state: {
          fromEventId: numericEventId,
          selectedSection: {
            section: sectionSnapshot.section,
            basePrice: sectionSnapshot.basePrice ?? null,
            seatCount,
          },
        },
      });
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) {
        // 422 means Redis pre-check failed (sold out). Mark badge locally so the user
        // gets immediate feedback while SSE catches up.
        const soldOut = selectedSection.section;
        queryClient.setQueryData<SectionAvailability[]>(
          sectionsKeys.byEvent(eventId),
          (curr) =>
            (curr ?? sections).map((s) =>
              s.section === soldOut ? { ...s, status: 'SOLD_OUT', availableCount: 0 } : s,
            ),
        );
        setSelectedSection(null);
        pushToast('該區已售完', { variant: 'error' });
        return;
      }
      // Other errors propagate so the modal renders inline error + retry CTA.
      throw e;
    }
  };

  return (
    <section className="mx-auto max-w-7xl px-6 py-10 md:py-14 flex flex-col gap-12">
      <header className="grid grid-cols-1 md:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] gap-10 md:gap-12 items-start">
        <div
          aria-hidden
          className="aspect-[3/4] w-full rounded-md border border-line-subtle relative overflow-hidden"
          style={{
            background: `linear-gradient(155deg, hsl(${(event.id * 67) % 360} 60% 14%), hsl(${(event.id * 67 + 80) % 360} 50% 24%))`,
          }}
        >
          <span className="absolute top-5 left-5 h-1 w-12 bg-accent" />
          <span className="absolute bottom-6 right-6 text-caption uppercase tracking-[0.18em] text-fg-primary/70 font-mono">
            EVT/{String(event.id).padStart(4, '0')}
          </span>
        </div>

        <div className="flex flex-col gap-6">
          <div className="flex items-center gap-3">
            <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
              / Event Detail
            </span>
            {salesNotStarted ? (
              <StatusPill variant="upcoming">UPCOMING</StatusPill>
            ) : allSoldOut ? (
              <StatusPill variant="sold-out" />
            ) : (
              <StatusPill variant="live" />
            )}
            <span
              className={cn(
                'ml-auto inline-flex items-center gap-2 text-caption uppercase tracking-[0.1em]',
                stream.connected ? 'text-status-plenty' : 'text-fg-tertiary',
              )}
              aria-live="polite"
            >
              <span
                aria-hidden
                className={cn(
                  'h-1.5 w-1.5 rounded-pill',
                  stream.connected ? 'bg-status-plenty animate-dot-pulse' : 'bg-fg-tertiary',
                )}
              />
              {stream.connected ? '即時連線中' : '重新連線中…'}
            </span>
          </div>

          <h1 className="text-display-lg md:text-display-xl font-extrabold tracking-tight leading-[1.05]">
            {event.name}
          </h1>

          <p className="text-body-lg text-fg-secondary max-w-xl">{event.description}</p>

          <dl className="grid grid-cols-2 sm:grid-cols-3 gap-y-3 gap-x-6 mt-2">
            <Meta label="表演者" value={event.performerName} />
            <Meta label="場館" value={event.venueName} />
            <Meta
              label="演出日期"
              value={new Date(event.eventStartTime).toLocaleString('zh-TW', {
                month: 'numeric',
                day: 'numeric',
                weekday: 'short',
                hour: '2-digit',
                minute: '2-digit',
              })}
            />
            <Meta label="總座位" value={event.totalSeats?.toLocaleString() ?? '—'} />
            <Meta label="票區數" value={event.sectionCount != null ? String(event.sectionCount) : '—'} />
          </dl>

          {salesNotStarted && (
            <div className="mt-2 flex flex-col gap-3">
              <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
                / Sales Open In
              </span>
              <SalesCountdown
                size="hero"
                salesStartAt={event.salesStartAt}
                onElapsed={() => {
                  void sectionsQuery.refetch();
                }}
              />
            </div>
          )}

          {allSoldOut && (
            <p className="text-body-md text-fg-secondary border-l-2 border-line-strong pl-4 mt-2">
              本場已售完
            </p>
          )}
        </div>
      </header>

      <div className="flex flex-col gap-5">
        <div className="flex items-end justify-between gap-4">
          <h2 className="text-display-md font-extrabold tracking-tight">
            選擇票區
          </h2>
          <p className="text-caption uppercase tracking-[0.14em] text-fg-tertiary hidden md:block">
            點擊票區開始搶票
          </p>
        </div>

        {sectionsQuery.isLoading && <SectionsSkeleton />}

        {sectionsQuery.error && (
          <div className="border border-signal-error/40 bg-surface p-6 rounded-sm flex items-center justify-between gap-4">
            <p className="text-body-md text-signal-error">
              票區載入失敗：{(sectionsQuery.error as Error).message}
            </p>
            <Button variant="secondary" size="sm" onClick={() => sectionsQuery.refetch()}>
              重試
            </Button>
          </div>
        )}

        {!sectionsQuery.isLoading && !sectionsQuery.error && sections.length === 0 && (
          <p className="text-body-md text-fg-secondary">尚無票區資料</p>
        )}

        {sections.length > 0 && bookingMode === 'SEAT_LEVEL' && <SeatLevelPlaceholder />}

        {sections.length > 0 && bookingMode === 'SECTION_VISUAL' && parsedSeatMap && (
          <VenueMap
            seatMap={parsedSeatMap}
            sections={sections}
            onPick={handleBadgeClick}
            ariaLabel={`${event.venueName} 場館選區圖`}
          />
        )}

        {sections.length > 0 &&
          (bookingMode === 'SECTION_TEXT' ||
            (bookingMode === 'SECTION_VISUAL' && !parsedSeatMap)) && (
            <SectionList sections={sections} onPick={handleBadgeClick} />
          )}
      </div>

      {selectedSection && (
        <BookingConfirmModal
          open={!!selectedSection}
          event={event}
          section={selectedSection}
          onConfirm={handleConfirmBooking}
          onCancel={() => setSelectedSection(null)}
        />
      )}
    </section>
  );
}

function Meta({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-caption uppercase tracking-[0.12em] text-fg-tertiary">{label}</dt>
      <dd className="text-body-md text-fg-primary">{value}</dd>
    </div>
  );
}

function ErrorState({
  title,
  detail,
  onRetry,
}: {
  title: string;
  detail?: string;
  onRetry?: () => void;
}) {
  return (
    <section className="mx-auto max-w-2xl px-6 py-24 text-center flex flex-col items-center gap-5">
      <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">/ 404</span>
      <h1 className="text-display-md font-extrabold tracking-tight">{title}</h1>
      {detail && <p className="text-body-lg text-fg-secondary">{detail}</p>}
      <div className="flex items-center gap-3 mt-4">
        {onRetry && (
          <Button variant="secondary" onClick={onRetry}>
            重試
          </Button>
        )}
        <Link
          to="/"
          className="text-caption uppercase tracking-[0.12em] text-accent hover:text-accent-hover transition-colors"
        >
          回活動列表 →
        </Link>
      </div>
    </section>
  );
}

function EventDetailSkeleton() {
  return (
    <section className="mx-auto max-w-7xl px-6 py-10 md:py-14 flex flex-col gap-12">
      <div className="grid grid-cols-1 md:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] gap-10 items-start">
        <div className="aspect-[3/4] w-full bg-surface border border-line-subtle rounded-md animate-pulse" />
        <div className="flex flex-col gap-4">
          <div className="h-3 w-32 bg-surface-2 animate-pulse" />
          <div className="h-14 w-full bg-surface-2 animate-pulse" />
          <div className="h-14 w-3/4 bg-surface-2 animate-pulse" />
          <div className="h-4 w-2/3 bg-surface animate-pulse mt-3" />
          <div className="h-4 w-1/2 bg-surface animate-pulse" />
        </div>
      </div>
      <SectionsSkeleton />
    </section>
  );
}

function SectionsSkeleton() {
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
      {Array.from({ length: 8 }).map((_, i) => (
        <div
          key={i}
          className="h-24 bg-surface border border-line-subtle rounded-sm animate-pulse"
        />
      ))}
    </div>
  );
}
