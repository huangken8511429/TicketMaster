import { Link } from 'react-router-dom';
import { Button } from '@/components/Button';

export function NotFoundPage() {
  return (
    <section className="mx-auto max-w-2xl px-6 py-20 flex flex-col items-start gap-6">
      <span className="text-caption uppercase tracking-[0.16em] text-fg-tertiary">/ 404</span>
      <h1 className="text-display-lg font-extrabold tracking-tight">
        這條路徑沒戲。
      </h1>
      <p className="text-body-lg text-fg-secondary">
        票場已散。但下一場可能正在開賣。
      </p>
      <Link to="/">
        <Button variant="primary">回活動列表</Button>
      </Link>
    </section>
  );
}
