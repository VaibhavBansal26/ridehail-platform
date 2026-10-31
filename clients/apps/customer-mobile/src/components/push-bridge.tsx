import { type Href, useRouter } from "expo-router";
import * as Notifications from "expo-notifications";
import { useEffect } from "react";
import { Platform } from "react-native";
import { registerForPush, routeForNotification } from "@/lib/push";

/**
 * Inside the signed-in app: quietly re-registers this phone if permission was already given, and opens the right screen when a
 * notification is tapped (including one that launched the app).
 */
export function PushBridge() {
  const router = useRouter();
  const response = Notifications.useLastNotificationResponse();

  useEffect(() => {
    void registerForPush({ prompt: false });
  }, []);

  useEffect(() => {
    if (Platform.OS === "web" || !response || response.actionIdentifier !== Notifications.DEFAULT_ACTION_IDENTIFIER) return;
    router.navigate(routeForNotification(response.notification.request.content.data) as Href);
  }, [response, router]);

  return null;
}
