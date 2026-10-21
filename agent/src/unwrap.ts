/**
 * Executor (and the app tools it wraps) return MCP CallToolResult envelopes:
 *   { content: [{ type: "text", text: "..." }, ...], structuredContent?: {...}, isError?: boolean }
 * Often nested: `execute` returns an envelope whose text is the JSON of the
 * script's return value, which itself contains envelopes from inner app tools.
 * Handing those raw to the model is the main cause of wrong answers, so we
 * unwrap recursively to plain data before the model sees anything.
 */

const MAX_TOOL_RESULT_CHARS = 12_000;

type Envelope = { content?: unknown; structuredContent?: unknown; isError?: boolean };

function isEnvelope(v: unknown): v is Envelope {
  if (!v || typeof v !== "object" || Array.isArray(v)) return false;
  const o = v as Record<string, unknown>;
  const keys = Object.keys(o);
  const allowed = new Set(["content", "structuredContent", "isError", "_meta"]);
  return (Array.isArray(o.content) || "structuredContent" in o) && keys.every((k) => allowed.has(k));
}

function tryParseJson(s: string): unknown {
  const t = s.trim();
  if (!(t.startsWith("{") || t.startsWith("[") || t.startsWith('"'))) return s;
  try {
    return JSON.parse(t);
  } catch {
    return s;
  }
}

export function unwrap(value: unknown, depth = 0): unknown {
  if (depth > 8) return value;
  if (typeof value === "string") {
    const parsed = tryParseJson(value);
    return parsed === value ? value : unwrap(parsed, depth + 1);
  }
  if (Array.isArray(value)) return value.map((v) => unwrap(v, depth + 1));
  if (isEnvelope(value)) {
    if (value.structuredContent !== undefined && value.structuredContent !== null) {
      const inner = unwrap(value.structuredContent, depth + 1);
      return value.isError ? { error: inner } : inner;
    }
    const parts = (Array.isArray(value.content) ? value.content : []).map((c: any) => {
      if (c?.type === "text") return unwrap(c.text, depth + 1);
      if (c?.type === "resource") return unwrap(c.resource?.text ?? c.resource?.uri, depth + 1);
      if (c?.type === "image" || c?.type === "audio") return `[${c.type} omitted]`;
      return unwrap(c, depth + 1);
    });
    const inner = parts.length === 1 ? parts[0] : parts;
    return value.isError ? { error: inner } : inner;
  }
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) out[k] = unwrap(v, depth + 1);
    return out;
  }
  return value;
}

/** Unwrap and serialise for a `tool` message, truncated to keep context small. */
export function toolResultForModel(result: unknown): string {
  const plain = unwrap(result);
  let s = typeof plain === "string" ? plain : JSON.stringify(plain);
  if (s.length > MAX_TOOL_RESULT_CHARS) {
    s = s.slice(0, MAX_TOOL_RESULT_CHARS) + `… [truncated ${s.length - MAX_TOOL_RESULT_CHARS} chars; narrow the query]`;
  }
  return s;
}
