import { Platform } from "react-native";

/**
 * Where the Ridehail API is. Set EXPO_PUBLIC_API_URL for a real device or a deployed API; the defaults reach an API running
 * on this computer from a simulator (iOS) or an emulator (Android reaches the host at 10.0.2.2).
 */
export const API_URL: string = (process.env.EXPO_PUBLIC_API_URL || (Platform.OS === "android" ? "http://10.0.2.2:8080" : "http://localhost:8080")).replace(/\/$/, "");
