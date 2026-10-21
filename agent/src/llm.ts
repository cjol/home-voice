import type { Env } from "./env";

export type ChatMessage =
  | { role: "system" | "user"; content: string }
  | { role: "assistant"; content: string | null; tool_calls?: ToolCall[] }
  | { role: "tool"; tool_call_id: string; content: string };

export interface ToolCall {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
}

export interface ToolDef {
  type: "function";
  function: { name: string; description?: string; parameters: unknown };
}

/**
 * Models that fail at tool calling through Executor (from the model benchmark).
 * They are rejected as a configured model rather than silently degrading.
 */
export const NON_TOOL_MODELS: Record<string, string> = {
  "@cf/mistralai/mistral-small-3.1-24b-instruct": "rejects requests that include tools",
  "@cf/meta/llama-4-scout-17b-16e-instruct": "prints tool calls as text instead of making them",
};

export interface StreamResult {
  text: string;
  toolCalls: ToolCall[];
}

/**
 * Streams a chat completion from Workers AI. Handles both the OpenAI-compatible
 * delta format (DeepSeek, gpt-oss, newer models) and the legacy `{response, tool_calls}`
 * format so LLM_MODEL can be swapped without code changes.
 */
export async function streamChat(
  env: Env,
  model: string,
  messages: ChatMessage[],
  tools: ToolDef[],
  onText: (delta: string) => void,
  signal: AbortSignal,
): Promise<StreamResult> {
  const input: Record<string, unknown> = { messages, stream: true, max_tokens: 1024 };
  if (tools.length) input.tools = tools;
  if (model.includes("gpt-oss")) input.reasoning_effort = "low"; // latency matters more than depth here

  const stream = (await env.AI.run(model as any, input as any, { signal })) as unknown as ReadableStream<Uint8Array>;

  let text = "";
  const calls = new Map<number, ToolCall>();
  const decoder = new TextDecoder();
  let buf = "";

  const reader = stream.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let nl: number;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line.startsWith("data:")) continue;
        const data = line.slice(5).trim();
        if (!data || data === "[DONE]") continue;
        let evt: any;
        try {
          evt = JSON.parse(data);
        } catch {
          continue;
        }
        const delta = evt.choices?.[0]?.delta;
        const piece: string | undefined = delta?.content ?? (typeof evt.response === "string" ? evt.response : undefined);
        if (piece) {
          text += piece;
          onText(piece);
        }
        for (const tc of delta?.tool_calls ?? []) {
          const idx = tc.index ?? 0;
          const cur = calls.get(idx) ?? { id: tc.id ?? `call_${idx}`, type: "function" as const, function: { name: "", arguments: "" } };
          if (tc.id) cur.id = tc.id;
          if (tc.function?.name) cur.function.name += tc.function.name;
          if (tc.function?.arguments) cur.function.arguments += typeof tc.function.arguments === "string" ? tc.function.arguments : JSON.stringify(tc.function.arguments);
          calls.set(idx, cur);
        }
        // Legacy Workers AI format: whole tool calls in one event.
        for (const [i, tc] of (evt.tool_calls ?? []).entries()) {
          calls.set(1000 + i, {
            id: tc.id ?? `call_l${i}`,
            type: "function",
            function: { name: tc.name ?? tc.function?.name, arguments: typeof tc.arguments === "string" ? tc.arguments : JSON.stringify(tc.arguments ?? tc.function?.arguments ?? {}) },
          });
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
  return { text, toolCalls: [...calls.values()].filter((c) => c.function.name) };
}
