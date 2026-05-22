import type { SectionAvailability } from '@/api/types';
import { SectionBadge } from './SectionBadge';

type Props = {
  sections: SectionAvailability[];
  onPick: (section: SectionAvailability) => void;
};

export function SectionList({ sections, onPick }: Props) {
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
      {sections.map((section) => (
        <SectionBadge
          key={section.section}
          section={section.section}
          status={section.status}
          onClick={() => onPick(section)}
        />
      ))}
    </div>
  );
}
