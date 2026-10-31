import { bearerAuth, createApi } from "@ridehail/api";
import { API_URL } from "@/lib/config";
import { tokenStore } from "@/lib/tokens";

let signedOut: () => void = () => undefined;
/** The session registers here to hear about a refresh token the server refused. */
export function onSignedOut(handler: () => void) {
  signedOut = handler;
}

export const auth = bearerAuth({ store: tokenStore, baseUrl: API_URL, onSignedOut: () => signedOut() });
export const api = createApi({ baseUrl: API_URL, auth });
