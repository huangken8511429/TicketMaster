# Frontend MVP — Design Tokens

**Stage**: `/spec`
**視覺方向具體化：色票、字體、間距、動效時間**

---

## 1. 設計原則（來自 UI/UX decision）

- **避開**：拓元的表格感、pastel + soft shadow 的「AI SaaS 美感」
- **追求**：editorial（編輯式排版）、高對比、有個性
- **關鍵字**：`impeccable` + `bolder`
- **基底**：深色背景 + 強烈 accent + 個性 Sans-Serif

---

## 2. 色票（Dark mode primary）

### 2.1 Base palette

| Token | Hex | 用途 |
|-------|-----|------|
| `--bg-ink` | `#0A0A0B` | 主背景（近黑，但不純黑——給留白呼吸） |
| `--bg-surface` | `#141416` | 卡片 / Section 容器 |
| `--bg-surface-2` | `#1C1C1F` | 卡片 hover / nested |
| `--bg-elevated` | `#26262A` | Modal / dropdown |
| `--border-subtle` | `#26262A` | 細邊框（與 elevated 同色，用於分隔） |
| `--border-strong` | `#3D3D42` | 強邊框（focus / 強調框） |

### 2.2 Foreground

| Token | Hex | 用途 |
|-------|-----|------|
| `--fg-primary` | `#F5F5F7` | 標題、主要文字（不純白，降反差眩光） |
| `--fg-secondary` | `#A8A8AE` | 次要文字、metadata |
| `--fg-tertiary` | `#6E6E73` | placeholder、disabled |
| `--fg-inverse` | `#0A0A0B` | 在 accent 上的文字 |

### 2.3 Accent — Signature color

**選擇**：**Acid Lime `#D6FF3D`**

理由：
- 不是市場上常見的紫紅藍（不會和拓元/KKTIX/Klook 撞色）
- 高對比、有「電子搶票」緊張感、editorial 雜誌常用
- WCAG AA 合格（搭配 ink 背景對比度 14:1）

| Token | Hex | 用途 |
|-------|-----|------|
| `--accent` | `#D6FF3D` | 主 CTA、倒數數字、focus ring |
| `--accent-hover` | `#E3FF66` | hover state |
| `--accent-pressed` | `#B8E020` | pressed state |
| `--accent-muted` | `#3D4717` | 低調 accent（icon、small label background） |

### 2.4 Semantic — 票區狀態 4 色

票區徽章是核心元件，4 個狀態必須**清晰可辨**（色弱友善的話用形狀輔助：實心圓、半圓、三角、灰底）。

| 狀態 | Token | Hex | 文案 |
|------|-------|-----|------|
| 熱賣中 (>30%) | `--status-plenty` | `#34D399` | 熱賣中 |
| 即將售完 (5-30%) | `--status-limited` | `#FBBF24` | 即將售完 |
| 僅剩數張 (<5%) | `--status-few` | `#F87171` | 僅剩數張（脈衝動畫） |
| 已售完 | `--status-sold-out` | `#52525B` | 已售完（disabled） |

形狀輔助（畫在徽章左側 8px dot）：
- plenty: solid circle ●
- limited: half circle ◐
- few: triangle ▲（脈衝）
- sold-out: empty circle ○

### 2.5 Error / Warning

| Token | Hex | 用途 |
|-------|-----|------|
| `--error` | `#F87171` | 錯誤 toast、表單 invalid |
| `--warning` | `#FBBF24` | 警告 banner |
| `--info` | `#60A5FA` | 中性提示 |

---

## 3. 字體（Type system）

### 3.1 字體選擇

| 用途 | 字體 | 來源 | 理由 |
|------|------|------|------|
| UI Sans-Serif | **Inter Tight** | Google Fonts | editorial 取向、字寬緊湊、有個性但非裝飾性 |
| 等寬（倒數 + 票號） | **JetBrains Mono** | Google Fonts | 開源、字面飽滿、tabular figures 對齊倒數不抖 |
| Display（活動標題 hero）| **Inter Tight Display weights (800-900)** | 同上 | 不引第二款 Display 字體，用 weight 區隔節奏 |

**Loading**：用 `display=swap` + preload subset（latin + cjk）；本地字重 400/500/700/800。

### 3.2 Type scale（rem，基底 16px）

| Token | px | rem | line-height | letter-spacing | 用途 |
|-------|-----|-----|-------------|----------------|------|
| `--text-display-xl` | 72 | 4.5 | 1.05 | -0.02em | Hero 活動名 |
| `--text-display-lg` | 56 | 3.5 | 1.05 | -0.02em | 倒數元件主數字 |
| `--text-display-md` | 40 | 2.5 | 1.1 | -0.015em | 畫面標題 |
| `--text-heading-lg` | 28 | 1.75 | 1.2 | -0.01em | Section heading |
| `--text-heading-md` | 22 | 1.375 | 1.25 | -0.01em | 卡片標題 |
| `--text-body-lg` | 18 | 1.125 | 1.45 | 0 | 主要內文 |
| `--text-body-md` | 16 | 1 | 1.5 | 0 | 一般內文 |
| `--text-body-sm` | 14 | 0.875 | 1.5 | 0 | 次要資訊、tag |
| `--text-caption` | 12 | 0.75 | 1.45 | 0.02em | 標籤、metadata |
| `--text-mono-display` | 64 | 4 | 1 | -0.02em | 倒數數字（JetBrains Mono 700） |

### 3.3 Font weights

| Weight | 用途 |
|--------|------|
| 400 | body |
| 500 | medium emphasis、UI labels |
| 700 | section headings |
| 800 | display headings、CTA |

---

## 4. 間距系統（4px base）

| Token | px | 用途 |
|-------|-----|------|
| `--space-0` | 0 | reset |
| `--space-1` | 4 | inline gap |
| `--space-2` | 8 | 緊湊 padding |
| `--space-3` | 12 | 元件內 padding |
| `--space-4` | 16 | 卡片 padding |
| `--space-5` | 24 | 元件之間 |
| `--space-6` | 32 | section 間距 |
| `--space-8` | 48 | 大區塊間距 |
| `--space-10` | 64 | 章節分隔 |
| `--space-12` | 96 | Hero block 上下 |
| `--space-16` | 128 | 留白呼吸（散客導向） |

**Editorial 原則**：寧大勿小。畫面 1 海報卡片之間用 `--space-6` 以上，畫面 2 hero 上下 `--space-12`+。

---

## 5. 邊角與邊框

| Token | 值 | 用途 |
|-------|-----|------|
| `--radius-none` | 0 | editorial 銳利感（首選） |
| `--radius-sm` | 4px | 小元件（badge、tag） |
| `--radius-md` | 8px | 卡片、modal |
| `--radius-lg` | 12px | 大卡片 |
| `--radius-pill` | 9999px | pill button、status badge |

**注意**：MVP 多數元件用 `--radius-none` 或 `--radius-sm`，**避開** 16px+ 的圓角（會像 SaaS）。

| Token | 值 | 用途 |
|-------|-----|------|
| `--border-w-1` | 1px | 標準邊框 |
| `--border-w-2` | 2px | focus / 強調 |

---

## 6. 陰影（克制使用）

Editorial 設計**克制陰影**，主要靠對比與邊框分層。

| Token | 值 | 用途 |
|-------|-----|------|
| `--shadow-none` | none | 預設 |
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.4)` | 卡片 hover |
| `--shadow-md` | `0 4px 12px rgba(0,0,0,0.5)` | modal |
| `--shadow-glow-accent` | `0 0 24px rgba(214,255,61,0.3)` | CTA hover 發光感 |

---

## 7. 動效時間（Motion tokens）

| Token | 值 | 用途 |
|-------|-----|------|
| `--motion-instant` | 0ms | 立即（圖案切換） |
| `--motion-fast` | 120ms | hover、focus |
| `--motion-base` | 200ms | 一般狀態切換 |
| `--motion-slow` | 320ms | modal / page transition |
| `--motion-slower` | 600ms | 票區徽章狀態變化（柔和察覺） |
| `--motion-pulse` | 1600ms | 「僅剩數張」徽章 pulse 週期 |
| `--motion-queue-cycle` | 2400ms | 排隊動畫主循環 |

### Easing curves

| Token | 值 | 用途 |
|-------|-----|------|
| `--ease-standard` | `cubic-bezier(0.2, 0, 0, 1)` | 預設（Material standard） |
| `--ease-decel` | `cubic-bezier(0, 0, 0.2, 1)` | 進場 |
| `--ease-accel` | `cubic-bezier(0.4, 0, 1, 1)` | 退場 |
| `--ease-snap` | `cubic-bezier(0.65, 0, 0.35, 1)` | 倒數每秒切換（snap） |

### Reduced motion

`@media (prefers-reduced-motion: reduce)`：
- 排隊動畫降為靜態
- 倒數仍切數字但無 snap
- 票區徽章狀態切換無 transition

---

## 8. Breakpoints（桌面優先，但保留基本 RWD）

| Token | min-width | 用途 |
|-------|-----------|------|
| `--bp-md` | 768px | tablet（畫面 1 grid 2 col） |
| `--bp-lg` | 1024px | desktop（畫面 1 grid 3 col、畫面 2 二欄） |
| `--bp-xl` | 1440px | wide（畫面 1 grid 4 col） |

MVP **不**做手機優化，但 768px 以下不可破版（簡單堆疊單欄）。

---

## 9. Z-index 階層

| Token | 值 | 用途 |
|-------|-----|------|
| `--z-base` | 0 | 預設 |
| `--z-sticky` | 10 | sticky header |
| `--z-dropdown` | 100 | dropdown |
| `--z-modal-backdrop` | 1000 | modal backdrop |
| `--z-modal` | 1010 | modal content |
| `--z-toast` | 2000 | toast |
| `--z-queue-overlay` | 9000 | 排隊全屏（畫面 3） |

---

## 10. Tailwind 整合建議

`tailwind.config.ts` 把上述 token 註冊為 theme extension：

```ts
theme: {
  extend: {
    colors: {
      ink: 'var(--bg-ink)',
      surface: { DEFAULT: 'var(--bg-surface)', 2: 'var(--bg-surface-2)', elevated: 'var(--bg-elevated)' },
      fg: { primary: 'var(--fg-primary)', secondary: 'var(--fg-secondary)', tertiary: 'var(--fg-tertiary)' },
      accent: { DEFAULT: 'var(--accent)', hover: 'var(--accent-hover)', pressed: 'var(--accent-pressed)' },
      status: { plenty: 'var(--status-plenty)', limited: 'var(--status-limited)', few: 'var(--status-few)', soldOut: 'var(--status-sold-out)' },
    },
    fontFamily: {
      sans: ['"Inter Tight"', 'system-ui', 'sans-serif'],
      mono: ['"JetBrains Mono"', 'monospace'],
    },
    transitionTimingFunction: {
      standard: 'cubic-bezier(0.2, 0, 0, 1)',
      snap: 'cubic-bezier(0.65, 0, 0.35, 1)',
    },
  }
}
```

CSS variables 同時定義在 `:root`，方便動態切換或 storybook 使用。

---

## 11. 不做的事（顯式排除）

- ❌ 多色漸層（`linear-gradient` 大面積）—— editorial 重對比不重花俏
- ❌ Glassmorphism / backdrop-blur 卡片（SaaS 制式）
- ❌ 多種圓角（統一銳利或微圓）
- ❌ Stock illustration（用大字 typography 和幾何 shape 取代）
- ❌ Light mode（MVP 不做，未來再說）
