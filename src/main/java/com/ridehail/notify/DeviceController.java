package com.ridehail.notify;

import com.ridehail.common.ApiExceptions.ForbiddenException;
import com.ridehail.security.CurrentUser;
import com.ridehail.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** A phone tells the server where to send it notifications. Only customers and riders have phones to notify. */
@RestController
@RequestMapping("/api/devices")
class DeviceController {
    private final DeviceTokenRepository devices;
    private final NotificationProperties props;
    private final Clock clock;

    DeviceController(DeviceTokenRepository devices, NotificationProperties props, Clock clock) {
        this.devices = devices;
        this.props = props;
        this.clock = clock;
    }

    record RegisterRequest(
            @NotBlank @Size(max = 200) @Pattern(regexp = "^Expo(nent)?PushToken\\[[A-Za-z0-9_-]{8,}]$", message = "not a push token")
            String token,
            @NotBlank @Pattern(regexp = "^(ios|android)$", message = "must be ios or android") String platform) {}

    record UnregisterRequest(@NotBlank @Size(max = 200) String token) {}

    /** Register (or re-register) this phone. Idempotent: the app calls it on every launch. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterRequest r) {
        Role role = CurrentUser.role(jwt);
        if (role != Role.CUSTOMER && role != Role.RIDER) throw new ForbiddenException("Only customers and riders have devices");
        UUID profile = CurrentUser.profileId(jwt);
        Instant now = Instant.now(clock);

        devices.findById(r.token()).ifPresentOrElse(
                // the same phone, signed in as someone else now: it follows the new account and stops getting the old one's
                existing -> existing.claim(profile, role.name(), r.platform(), now),
                () -> devices.save(new DeviceToken(r.token(), profile, role.name(), r.platform(), now)));
        devices.flush();

        // an account that never signs out of old phones must not collect tokens forever: keep the most recently used few
        // (the phone being registered right now is never the one dropped, whatever the clocks say)
        List<DeviceToken> others = devices.findByProfileIdOrderByLastSeenAtDesc(profile).stream()
                .filter(d -> !d.getToken().equals(r.token())).toList();
        int room = Math.max(props.maxDevicesPerAccount() - 1, 0);
        if (others.size() > room) {
            devices.deleteByTokenIn(others.subList(room, others.size()).stream().map(DeviceToken::getToken).toList());
        }
    }

    /** Sign-out: stop notifying this phone about this account. Only the owner can remove a token. */
    @PostMapping("/unregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void unregister(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UnregisterRequest r) {
        devices.deleteByTokenAndProfileId(r.token(), CurrentUser.profileId(jwt));
    }
}
