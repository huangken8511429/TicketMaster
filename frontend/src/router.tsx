import { createBrowserRouter, Navigate } from 'react-router-dom';
import { ConfirmPage } from '@/pages/ConfirmPage';
import { EventDetailPage } from '@/pages/EventDetailPage';
import { EventsListPage } from '@/pages/EventsListPage';
import { Layout } from '@/pages/Layout';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { QueuePage } from '@/pages/QueuePage';

export const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <EventsListPage /> },
      { path: 'events', element: <Navigate to="/" replace /> },
      { path: 'events/:id', element: <EventDetailPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
  // Queue + Confirm render full-bleed without the chrome layout.
  { path: '/queue/:bookingId', element: <QueuePage /> },
  { path: '/confirm/:bookingId', element: <ConfirmPage /> },
]);
