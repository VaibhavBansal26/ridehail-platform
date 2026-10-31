package com.ridehail.verification;

import com.ridehail.common.ApiExceptions.BadRequestException;
import com.ridehail.common.ApiExceptions.PayloadTooLargeException;
import com.ridehail.security.CurrentUser;
import com.ridehail.verification.VerificationService.View;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** The rider's own verification: see what is needed, send the documents, submit. ROLE_RIDER only (SecurityConfig). */
@RestController
@RequestMapping("/api/riders/me/verification")
class RiderVerificationController {
    private final VerificationService verification;
    private final VerificationProperties props;

    RiderVerificationController(VerificationService verification, VerificationProperties props) {
        this.verification = verification;
        this.props = props;
    }

    @GetMapping
    View view(@AuthenticationPrincipal Jwt jwt) {
        return verification.view(CurrentUser.profileId(jwt));
    }

    /**
     * Send one document as the raw request body (a JPEG, PNG or, except for the selfie, a PDF). `expiresOn` (YYYY-MM-DD) is required
     * for the licence, registration and insurance. Sending the same type again replaces the earlier one.
     */
    @PutMapping("/documents/{type}")
    View upload(@AuthenticationPrincipal Jwt jwt, @PathVariable DocumentType type,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
                HttpServletRequest request) throws IOException {
        int max = props.maxDocumentBytes();
        // refuse before reading, and never read more than the limit plus one byte, whatever the client claims
        if (request.getContentLengthLong() > max) throw new PayloadTooLargeException("The file is larger than " + max / (1024 * 1024) + " MB");
        byte[] bytes = request.getInputStream().readNBytes(max + 1);
        if (bytes.length > max) throw new PayloadTooLargeException("The file is larger than " + max / (1024 * 1024) + " MB");
        if (bytes.length == 0) throw new BadRequestException("The file is empty");
        return verification.upload(CurrentUser.profileId(jwt), type, bytes, expiresOn);
    }

    @PostMapping("/submit")
    View submit(@AuthenticationPrincipal Jwt jwt) {
        return verification.submit(CurrentUser.profileId(jwt));
    }
}
