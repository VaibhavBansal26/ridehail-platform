package com.ridehail.security;

import com.ridehail.common.VehicleType;
import com.ridehail.security.AuthService.AuthResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService auth;
    private final LoginRateLimiter loginLimit;

    AuthController(AuthService auth, LoginRateLimiter loginLimit) {
        this.auth = auth;
        this.loginLimit = loginLimit;
    }

    record RegisterCustomer(@NotBlank String name, @NotBlank String phone, @NotBlank @Size(min = 8, max = 72) String password) {}
    record RegisterRider(@NotBlank String name, @NotBlank String phone, @NotBlank @Size(min = 8, max = 72) String password,
                         @NotNull VehicleType vehicleType) {}
    record Login(@NotBlank String phone, @NotBlank String password) {}
    record Refresh(@NotBlank String refreshToken) {}

    @PostMapping("/register/customer")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResult registerCustomer(@Valid @RequestBody RegisterCustomer r) {
        return auth.registerCustomer(r.name(), r.phone().trim(), r.password());
    }

    @PostMapping("/register/rider")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResult registerRider(@Valid @RequestBody RegisterRider r) {
        return auth.registerRider(r.name(), r.phone().trim(), r.password(), r.vehicleType());
    }

    @PostMapping("/login")
    AuthResult login(@Valid @RequestBody Login r) {
        loginLimit.check(r.phone());
        return auth.login(r.phone().trim(), r.password());
    }

    @PostMapping("/refresh")
    AuthResult refresh(@Valid @RequestBody Refresh r) {
        return auth.refresh(r.refreshToken());
    }
}
