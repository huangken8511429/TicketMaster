# SSE Deployment Notes

> Phase 7 hand-off. This document lists the reverse-proxy and ingress
> configurations required for the `/api/events/:id/sections/stream` SSE endpoint
> and the `/api/bookings/:bookingId` long-poll endpoint to behave correctly in
> production.

The Spring Boot backend already does the right thing at the application layer
(`X-Accel-Buffering: no`, `Cache-Control: no-cache`, `Connection: keep-alive`).
The risk is that an intermediate proxy buffers events until the response is
closed, which would cause "first event arrives after 60s" symptoms.

---

## 1. nginx (most common)

```nginx
# SSE — long-lived response, one event at a time. Buffering MUST be disabled.
location ~ ^/api/events/.+/sections/stream$ {
    proxy_pass         http://backend;
    proxy_http_version 1.1;

    # Critical: no buffering, no caching, keep the connection open.
    proxy_buffering     off;
    proxy_cache         off;
    proxy_set_header    Connection '';
    proxy_read_timeout  3600s;   # 1 hour — SSE is long-lived by design
    proxy_send_timeout  3600s;

    # Disable nginx's own response chunking on top of Spring's stream.
    chunked_transfer_encoding off;

    # Forward the no-buffer hint downstream too (if there's another proxy).
    add_header X-Accel-Buffering no always;
}

# Long-poll — request can sit open up to ~10s. Read timeout must comfortably
# exceed the server-side DeferredResult timeout (default 10s, with retry
# headroom recommended).
location ~ ^/api/bookings/.+$ {
    proxy_pass         http://backend;
    proxy_http_version 1.1;
    proxy_read_timeout 30s;
    proxy_send_timeout 30s;
}
```

If the upstream is itself another nginx (e.g. ingress-nginx → kube-proxy →
pod), each hop must set `proxy_buffering off`.

---

## 2. Kubernetes ingress-nginx

Annotate the ingress (`metadata.annotations`):

```yaml
nginx.ingress.kubernetes.io/proxy-buffering: "off"
nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
# Optional but recommended for SSE
nginx.ingress.kubernetes.io/configuration-snippet: |
  chunked_transfer_encoding off;
```

If you need per-path configuration (long-poll separate from SSE), split the
ingress into two `Ingress` resources or use the
`nginx.ingress.kubernetes.io/server-snippet` escape hatch.

---

## 3. Cloud load balancers

| Provider                  | Required setting                                                                    |
| ------------------------- | ----------------------------------------------------------------------------------- |
| AWS ALB                   | Idle timeout ≥ 300s for SSE path; ALB does not buffer, but increase if needed.      |
| GCP HTTPS LB              | Backend service timeout ≥ 300s. Disable Cloud CDN for `/api/*`.                     |
| Cloudflare                | "100 — Continue" trick is on by default; enable "Sandbox" or set Page Rule to bypass cache for `/api/*`. Free tier has a hard 100s response cap that will kill SSE — use a paid tier for prod. |
| Azure App Gateway / AFD   | `responseBufferingEnabled: false` on the backend setting.                           |

---

## 4. Application-layer guarantees already in place

The frontend and backend both ship the safety belts:

- **Backend** (`SectionStatusStreamController`):
  ```http
  Content-Type: text/event-stream
  Cache-Control: no-cache
  Connection: keep-alive
  X-Accel-Buffering: no
  ```
- **Backend** writes a `heartbeat` event every 15 seconds so even a perfectly
  buffered proxy will be flushed periodically.
- **Frontend** (`useSectionStatusStream` hook) sets `withCredentials: false`
  and listens for `error` events to drive a reconnect-with-backoff loop.
- **Frontend** re-fetches `/api/events/:id/sections` on reconnect to plug any
  gap between the last `id:` it saw and the new stream's first event.

---

## 5. Deployment validation checklist

Before declaring SSE healthy in a new environment:

1. `curl -N -H 'Accept: text/event-stream' <host>/api/events/1/sections/stream`
   — first event should arrive within 100ms; events should continue every
   ~4s; heartbeat events every 15s.
2. Open `<host>/events/1` in a browser, open DevTools → Network → Type =
   EventStream. The `sections/stream` request should:
   - Show **`Time: pending`** indefinitely (not 0ms with status 200).
   - Show event frames updating live in the "EventStream" tab.
   - Reconnect automatically if you toggle airplane mode (UI badge flips
     from "即時連線中" to "重新連線中…" and back).
3. Submit a booking. The `/api/bookings/:id` long-poll should sit at
   `pending` for up to ~10s before resolving — not get truncated mid-flight.

If the curl test passes but the browser test fails, suspect a CORS or
service-worker issue (MSW must NOT register in production — Phase 7 already
gated this in `main.tsx`).

---

## 6. Known follow-ups

- **Cloudflare Free tier**: SSE responses are killed at 100s. Either upgrade
  to Pro or fall back to long-polling for the section status (the existing
  `/api/events/:id/sections` endpoint can be polled every 5s as a graceful
  degradation).
- **HTTP/2 multiplexing**: most browsers cap concurrent SSE streams per origin
  at 6 over HTTP/1.1 but unlimited over HTTP/2. Ensure terminating proxy
  negotiates h2 with the browser.
