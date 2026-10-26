package com.ridehail.security;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.UnauthorizedException;
import com.ridehail.common.VehicleType;
import com.ridehail.customer.Customer;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.ledger.LedgerService;
import com.ridehail.rider.Rider;
import com.ridehail.rider.RiderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserAccountRepository accounts;
    private final CustomerRepository customers;
    private final RiderRepository riders;
    private final LedgerService ledger;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final Clock clock;
    private final String dummyHash;

    AuthService(UserAccountRepository accounts, CustomerRepository customers, RiderRepository riders,
                LedgerService ledger, PasswordEncoder passwords, JwtService jwt, Clock clock) {
        this.accounts = accounts;
        this.customers = customers;
        this.riders = riders;
        this.ledger = ledger;
        this.passwords = passwords;
        this.jwt = jwt;
        this.clock = clock;
        this.dummyHash = passwords.encode("timing-equalizer");
    }

    @Transactional
    public AuthResult registerCustomer(String name, String phone, String password) {
        requirePhoneFree(phone);
        Customer c = customers.save(new Customer(name, phone));
        return register(phone, password, Role.CUSTOMER, c.getId());
    }

    @Transactional
    public AuthResult registerRider(String name, String phone, String password, VehicleType vehicleType) {
        requirePhoneFree(phone);
        Rider r = riders.save(new Rider(name, phone, vehicleType));
        ledger.openRiderAccount(r.getId()); // every rider has an earnings account from day one
        return register(phone, password, Role.RIDER, r.getId());
    }

    @Transactional(readOnly = true)
    public AuthResult login(String phone, String password) {
        var account = accounts.findByPhone(phone).orElse(null);
        // always run one BCrypt check so unknown phones and wrong passwords take the same time
        boolean ok = passwords.matches(password, account == null ? dummyHash : account.getPasswordHash());
        if (account == null || !ok || !account.isEnabled()) throw new UnauthorizedException("Invalid credentials");
        return result(account);
    }

    @Transactional(readOnly = true)
    public AuthResult refresh(String refreshToken) {
        try {
            var account = accounts.findById(jwt.accountIdFromRefresh(refreshToken))
                    .filter(UserAccount::isEnabled)
                    .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
            return result(account);
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid refresh token");
        }
    }

    private AuthResult register(String phone, String password, Role role, UUID profileId) {
        var account = accounts.save(new UserAccount(phone, passwords.encode(password), role, profileId, Instant.now(clock)));
        return result(account);
    }

    private void requirePhoneFree(String phone) {
        if (accounts.findByPhone(phone).isPresent()) throw new ConflictException("Phone number already registered");
    }

    private AuthResult result(UserAccount a) {
        var t = jwt.issue(a);
        return new AuthResult(t.accessToken(), t.refreshToken(), a.getRole(), a.getProfileId());
    }

    public record AuthResult(String accessToken, String refreshToken, Role role, UUID profileId) {}
}
