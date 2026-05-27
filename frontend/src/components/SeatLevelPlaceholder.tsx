export function SeatLevelPlaceholder() {
  return (
    <div className="border border-line-subtle bg-surface p-10 rounded-md text-center flex flex-col gap-3">
      <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
        / Seat-Level Booking
      </span>
      <h3 className="text-display-md font-extrabold tracking-tight">逐座位選位</h3>
      <p className="text-body-md text-fg-secondary">此活動採逐座位選位，敬請期待。</p>
    </div>
  );
}
