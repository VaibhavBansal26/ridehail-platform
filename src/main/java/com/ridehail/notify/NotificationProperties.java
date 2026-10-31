package com.ridehail.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phone push notifications (the lock-screen kind, for when the app is closed). Off by default: nothing leaves the server until a
 * provider is chosen. "expo" sends through Expo's push service, which forwards to Apple and Google.
 */
@ConfigurationProperties(prefix = "ridehail.notifications")
public record NotificationProperties(String provider, String expoUrl, String expoAccessToken, int maxDevicesPerAccount) {
    public NotificationProperties {
        if (provider == null || provider.isBlank()) provider = "none";
        if (expoUrl == null || expoUrl.isBlank()) expoUrl = "https://exp.host/--/api/v2/push/send";
        if (maxDevicesPerAccount <= 0) maxDevicesPerAccount = 10;
    }

    public boolean expo() {
        return "expo".equalsIgnoreCase(provider);
    }
}
