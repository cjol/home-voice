// Inferred from cloudflare.config.ts by `cf workers types` (.cloudflare/types/index.d.ts).
export type Env = Cloudflare.Env & {
  /** Optional: OAuth callback host override; derived from the request when unset. */
  PUBLIC_URL?: string;
};
