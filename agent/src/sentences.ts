/** Splits streamed LLM text into speakable chunks as early as possible. */
export class SentenceChunker {
  private buf = "";
  constructor(private readonly minChars = 24) {}


  push(text: string): string[] {
    this.buf += text;
    const out: string[] = [];
    // Break at sentence end, or at a clause break once the chunk is long enough.
    const re = /([.!?…]+["')\]]?\s+)|([,;:—]\s+)|(\n+)/g;
    let last = 0;
    let m: RegExpExecArray | null;
    while ((m = re.exec(this.buf))) {
      const end = m.index + m[0].length;
      const candidate = this.buf.slice(last, end);
      const isClause = !!m[2];
      const len = candidate.trim().length;
      if (isClause ? len >= this.minChars * 2 : len >= 2) {
        out.push(candidate.trim());
        last = end;
      }
    }
    this.buf = this.buf.slice(last);
    return out.filter(Boolean);
  }

  flush(): string | null {
    const rest = this.buf.trim();
    this.buf = "";
    return rest || null;
  }
}

/** Strip markdown the model may emit despite instructions; TTS reads it literally. */
export function speakable(s: string): string {
  return s
    .replace(/```[\s\S]*?```/g, "")
    .replace(/[*_`#>]+/g, "")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}
