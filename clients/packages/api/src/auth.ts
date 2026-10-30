import { ApiError } from "./errors";
import { AuthResult } from "./schemas";

export interface Tokens {
  accessToken: string;
  refreshToken: string;
}

/**
 * Where the app keeps its tokens. On a phone this must be the OS keychain/keystore (expo-secure-store), never plain
 * AsyncStorage. Methods may be async.
 */
export interface TokenStore {
  load(): Promise<Tokens | null> | Tokens | null;
  save(tokens: Tokens | null): Promise<void> | void;
}

/** How a client proves who it is, and what it does when the server says its proof expired. */
export interface AuthStrategy {
  /** Extra headers for an authenticated request (e.g. Authorization). */
  headers(): Promise<Record<string, string>>;
  /** The server answered 401. Return true if the request should be repeated once (a fresh session now exists). */
  refresh(): Promise<boolean>;
}

export interface BearerAuthOptions {
  store: TokenStore;
  baseUrl: string;
  fetch?: typeof fetch;
  /** Called when the refresh token is rejected: the person has to sign in again. Tokens are already cleared. */
  onSignedOut?: () => void;
}

/** In-memory token store, for tests and for the web client's WebSocket token. */
export function memoryTokenStore(initial: Tokens | null = null): TokenStore {
  let tokens = initial;
  return {
    load: () => tokens,
    save: (t) => {
      tokens = t;
    },
  };
}

/**
 * Mobile: an access token on every request and, on a 401, one refresh with the refresh token. Concurrent 401s share a
 * single refresh call (the server rotates refresh tokens, so two parallel refreshes would invalidate each other).
 * A refresh the server refuses signs the person out; a refresh that merely could not reach the server does not.
 */
export function bearerAuth(opts: BearerAuthOptions): AuthStrategy {
  const doFetch = opts.fetch ?? fetch;
  let inFlight: Promise<boolean> | null = null;

  async function refreshOnce(): Promise<boolean> {
    const tokens = await opts.store.load();
    if (!tokens) return false;
    let res: Response;
    try {
      res = await doFetch(opts.baseUrl.replace(/\/$/, "") + "/api/auth/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ refreshToken: tokens.refreshToken }),
      });
    } catch {
      throw new ApiError("Cannot reach the server. Check your connection.", 0);
    }
    if (res.status >= 500 || res.status === 429) throw new ApiError("The server had a problem. Try again.", res.status);
    if (!res.ok) {
      await opts.store.save(null);
      opts.onSignedOut?.();
      return false;
    }
    const parsed = AuthResult.safeParse(await res.json().catch(() => null));
    if (!parsed.success) throw new ApiError("The server sent an unexpected sign-in response.", res.status);
    await opts.store.save({ accessToken: parsed.data.accessToken, refreshToken: parsed.data.refreshToken });
    return true;
  }

  return {
    async headers(): Promise<Record<string, string>> {
      const tokens = await opts.store.load();
      return tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {};
    },
    refresh() {
      inFlight ??= refreshOnce().finally(() => {
        inFlight = null;
      });
      return inFlight;
    },
  };
}

export interface CookieAuthOptions {
  /** The web app's BFF holds the tokens in httpOnly cookies; the browser sends them itself. */
  extraHeaders?: Record<string, string>;
  /** The session cookie is gone or expired and the BFF could not renew it. */
  onSignedOut?: () => void;
}

/** Web: the same-origin BFF owns the session (httpOnly cookies) and refreshes it itself, so there is nothing to do here. */
export function cookieAuth(opts: CookieAuthOptions = {}): AuthStrategy {
  return {
    headers: async () => ({ ...opts.extraHeaders }),
    async refresh() {
      opts.onSignedOut?.();
      return false;
    },
  };
}

/** Save the tokens from a sign-in or registration response. */
export async function saveSession(store: TokenStore, result: AuthResult): Promise<void> {
  await store.save({ accessToken: result.accessToken, refreshToken: result.refreshToken });
}
