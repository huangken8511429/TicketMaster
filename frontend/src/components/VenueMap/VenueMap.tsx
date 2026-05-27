import { useMemo } from 'react';
import { cn } from '@/lib/cn';
import type { SectionAvailability, VenueSeatMap } from '@/api/types';
import { VenueMapStage } from './VenueMapStage';
import { VenueMapSection } from './VenueMapSection';

type Props = {
  seatMap: VenueSeatMap;
  sections: SectionAvailability[];
  onPick: (section: SectionAvailability) => void;
  className?: string;
  ariaLabel?: string;
};

export function VenueMap({ seatMap, sections, onPick, className, ariaLabel }: Props) {
  const availMap = useMemo(() => {
    const m = new Map<string, SectionAvailability>();
    for (const s of sections) m.set(s.section, s);
    return m;
  }, [sections]);

  return (
    <div className={cn('w-full', className)}>
      <svg
        viewBox={seatMap.viewBox}
        role="img"
        aria-label={ariaLabel ?? '場館選區圖'}
        className="w-full h-auto select-none"
        preserveAspectRatio="xMidYMid meet"
      >
        <VenueMapStage stage={seatMap.stage} />
        {seatMap.sections.map((s) => (
          <VenueMapSection
            key={s.name}
            section={s}
            availability={availMap.get(s.name)}
            onPick={onPick}
          />
        ))}
      </svg>
    </div>
  );
}
