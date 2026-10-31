import type { TokenStore, Tokens } from "@ridehail/api";
import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";

const KEY = "ridehail_rider_tokens";

function parse(raw: string | null): Tokens | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<Tokens>;
    return typeof v.accessToken === "string" && typeof v.refreshToken === "string" ? { accessToken: v.accessToken, refreshToken: v.refreshToken } : null;
  } catch {
    return null;
  }
}

/** The OS keychain (iOS) or keystore (Android). Never plain AsyncStorage: the refresh token is a week-long key to the account. */
const nativeStore: TokenStore = {
  load: async () => parse(await SecureStore.getItemAsync(KEY).catch(() => null)),
  save: async (t) => {
    if (t) await SecureStore.setItemAsync(KEY, JSON.stringify(t));
    else await SecureStore.deleteItemAsync(KEY);
  },
};

/**
 * Expo's web build has no secure storage. This exists so the app can be tried in a browser during development and is
 * deliberately not persistent: a reload signs you out.
 */
function memoryStore(): TokenStore {
  let tokens: Tokens | null = null;
  return { load: () => tokens, save: (t) => void (tokens = t) };
}

export const tokenStore: TokenStore = Platform.OS === "web" ? memoryStore() : nativeStore;
