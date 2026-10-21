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
}

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

export type UiMessage = CardUi | ListUi | ChartUi | StatusUi;
export type UiKind = UiMessage["kind"];

/** JSON Schema (draft-07 subset) used as the LLM tool's parameter schema. */
export const SHOW_UI_PARAMETERS = {
  type: "object",
  properties: {
    kind: { type: "string", enum: ["card", "list", "chart", "status"] },
    title: { type: "string" },
    ttlMs: { type: "integer", description: "Display time in ms, default 20000" },
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
    progress: { type: "number", minimum: 0, maximum: 1 },
  },
  required: ["kind"],
} as const;

/** Light validation of model-produced UI. Returns null if unusable. */
export function normalizeUi(raw: unknown, id: string): UiMessage | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const base = {
    v: UI_SCHEMA_VERSION,
    id,
    title: typeof r.title === "string" ? r.title : undefined,
    ttlMs: typeof r.ttlMs === "number" ? r.ttlMs : 20_000,
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
    default:
      return null;
  }
}

const str = (v: unknown) => (typeof v === "string" ? v : undefined);
const tone = (v: unknown): Tone | undefined =>
  v === "neutral" || v === "info" || v === "success" || v === "warning" || v === "error" ? v : undefined;
