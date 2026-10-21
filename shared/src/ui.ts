/**
 * Generative UI schema, version 1.
 *
 * The agent sends one of these inside a `ui` server message. The tablet renders
 * it in a full-screen overlay and dismisses it after `ttlMs` (default 20 s).
 * Kotlin mirror: android/app/src/main/java/net/littlesavage/homevoice/protocol/Ui.kt
 * JSON Schema:   shared/schema/ui.v1.schema.json (also the `show_ui` tool schema)
 *
 * Rules for evolving: add optional fields freely; anything else bumps `v`.
 * Clients must ignore unknown fields and render unknown `kind`s as a plain card.
 *
 * Screens are addressable: sending a UI with an existing `id` replaces that screen in place, which
 * is how the agent builds on a screen over several turns (e.g. a running carb table). `close_ui`
 * (server message `dismiss_ui`) removes it.
 */
export const UI_SCHEMA_VERSION = 1 as const;

export type Tone = "neutral" | "info" | "success" | "warning" | "error";

interface UiBase {
  v: typeof UI_SCHEMA_VERSION;
  /** Stable id; a later message with the same id replaces the earlier one. */
  id: string;
  title?: string;
  /** How long to keep it on screen. 0 = until replaced or dismissed. */
  ttlMs?: number;
  /** "panel" (default): inside the assistant panel over the current app. "fullscreen": takes over the screen. */
  display?: Display;
}

export type Display = "panel" | "fullscreen";

export interface CardUi extends UiBase {
  kind: "card";
  body: string;
  subtitle?: string;
  imageUrl?: string;
  tone?: Tone;
}

export interface ListItem {
  title: string;
  subtitle?: string;
  trailing?: string;
}

export interface ListUi extends UiBase {
  kind: "list";
  items: ListItem[];
}

export interface ChartUi extends UiBase {
  kind: "chart";
  chartType: "bar" | "line";
  unit?: string;
  series: { label: string; points: { x: string; y: number }[] }[];
}

export interface StatusUi extends UiBase {
  kind: "status";
  text: string;
  tone?: Tone;
  /** 0..1 for a progress bar; omit for none. */
  progress?: number;
}

export interface TableUi extends UiBase {
  kind: "table";
  columns: { label: string; align?: "start" | "end" }[];
  rows: string[][];
  /** e.g. a totals row; same width as `columns`. */
  footer?: string[];
  /** Small print under the table (sources, assumptions). */
  note?: string;
}

export type UiMessage = CardUi | ListUi | ChartUi | StatusUi | TableUi;
export type UiKind = UiMessage["kind"];

/** JSON Schema (draft-07 subset) used as the LLM tool's parameter schema. */
export const SHOW_UI_PARAMETERS = {
  type: "object",
  properties: {
    id: {
      type: "string",
      description: "Short stable id, e.g. \"carbs\". Reuse it to replace that screen in place (resend the full content).",
    },
    kind: { type: "string", enum: ["card", "list", "chart", "status", "table"] },
    title: { type: "string" },
    display: {
      type: "string",
      enum: ["panel", "fullscreen"],
      description: "panel for quick answers (default, except tables); fullscreen for things the user will keep working on (default for tables)",
    },
    ttlMs: { type: "integer", description: "Display time in ms; default 20000 (panel) or 0 (fullscreen); 0 = until closed" },
    body: { type: "string", description: "card: main text" },
    subtitle: { type: "string" },
    imageUrl: { type: "string" },
    tone: { type: "string", enum: ["neutral", "info", "success", "warning", "error"] },
    items: {
      type: "array",
      description: "list: rows",
      items: {
        type: "object",
        properties: {
          title: { type: "string" },
          subtitle: { type: "string" },
          trailing: { type: "string" },
        },
        required: ["title"],
      },
    },
    chartType: { type: "string", enum: ["bar", "line"] },
    unit: { type: "string" },
    series: {
      type: "array",
      description: "chart: data series",
      items: {
        type: "object",
        properties: {
          label: { type: "string" },
          points: {
            type: "array",
            items: {
              type: "object",
              properties: { x: { type: "string" }, y: { type: "number" } },
              required: ["x", "y"],
            },
          },
        },
        required: ["label", "points"],
      },
    },
    text: { type: "string", description: "status: one-line text" },
    columns: {
      type: "array",
      description: "table: column headers",
      items: {
        type: "object",
        properties: { label: { type: "string" }, align: { type: "string", enum: ["start", "end"] } },
        required: ["label"],
      },
    },
    rows: { type: "array", description: "table: rows of cell strings", items: { type: "array", items: { type: "string" } } },
    footer: { type: "array", description: "table: totals row", items: { type: "string" } },
    note: { type: "string", description: "table: small print, e.g. assumptions" },
    progress: { type: "number", minimum: 0, maximum: 1 },
  },
  required: ["kind"],
} as const;

/** Light validation of model-produced UI. Returns null if unusable. `fallbackId` is used when the model gave none. */
export function normalizeUi(raw: unknown, fallbackId: string): UiMessage | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  // Tables are usually built on over several turns, so they default to a persistent full screen.
  const display: Display = r.display === "fullscreen" || r.display === "panel" ? r.display : r.kind === "table" ? "fullscreen" : "panel";
  const base = {
    v: UI_SCHEMA_VERSION,
    id: typeof r.id === "string" && r.id.trim() ? r.id.trim().slice(0, 64) : fallbackId,
    title: typeof r.title === "string" ? r.title : undefined,
    ttlMs: typeof r.ttlMs === "number" ? Math.max(0, r.ttlMs) : display === "fullscreen" ? 0 : 20_000,
    display,
  };
  switch (r.kind) {
    case "card":
      return { ...base, kind: "card", body: String(r.body ?? ""), subtitle: str(r.subtitle), imageUrl: str(r.imageUrl), tone: tone(r.tone) };
    case "list":
      return {
        ...base,
        kind: "list",
        items: Array.isArray(r.items)
          ? r.items.filter((i) => i && typeof i === "object").map((i: any) => ({ title: String(i.title ?? ""), subtitle: str(i.subtitle), trailing: str(i.trailing) }))
          : [],
      };
    case "chart":
      return {
        ...base,
        kind: "chart",
        chartType: r.chartType === "line" ? "line" : "bar",
        unit: str(r.unit),
        series: Array.isArray(r.series)
          ? r.series.map((s: any) => ({
              label: String(s?.label ?? ""),
              points: Array.isArray(s?.points) ? s.points.map((p: any) => ({ x: String(p?.x ?? ""), y: Number(p?.y ?? 0) })) : [],
            }))
          : [],
      };
    case "status":
      return { ...base, kind: "status", text: String(r.text ?? r.body ?? ""), tone: tone(r.tone), progress: typeof r.progress === "number" ? r.progress : undefined };
    case "table": {
      const columns = Array.isArray(r.columns)
        ? r.columns.map((c: any) => (typeof c === "string" ? { label: c } : { label: String(c?.label ?? ""), align: c?.align === "end" ? ("end" as const) : undefined }))
        : [];
      const cells = (row: unknown) => (Array.isArray(row) ? row.map((c) => (c == null ? "" : String(c))) : []);
      return {
        ...base,
        kind: "table",
        columns,
        rows: Array.isArray(r.rows) ? r.rows.map(cells) : [],
        footer: Array.isArray(r.footer) ? cells(r.footer) : undefined,
        note: str(r.note),
      };
    }
    default:
      return null;
  }
}

const str = (v: unknown) => (typeof v === "string" ? v : undefined);
const tone = (v: unknown): Tone | undefined =>
  v === "neutral" || v === "info" || v === "success" || v === "warning" || v === "error" ? v : undefined;
