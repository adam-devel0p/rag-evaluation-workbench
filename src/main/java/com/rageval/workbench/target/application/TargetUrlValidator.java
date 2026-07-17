package com.rageval.workbench.target.application;

import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.ValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates a {@code STANDARD_HTTP} target's endpoint URL per spec §6.1: only {@code http}/{@code
 * https} are accepted, every other scheme (including {@code file}, {@code ftp}, {@code gopher}) is
 * rejected. {@code localhost} and other private/loopback hosts are deliberately allowed — this is
 * a local evaluation workbench, so pointing it at a target running on the same machine is a normal
 * setup, not an SSRF concern to guard against.
 */
@Component
public class TargetUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public void validate(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new ValidationException(
                    "endpointUrl is required for a STANDARD_HTTP target",
                    new FieldViolation("endpointUrl", "must not be blank"));
        }
        URI uri;
        try {
            uri = new URI(endpointUrl);
        } catch (URISyntaxException ex) {
            throw new ValidationException(
                    "endpointUrl is not a valid URI",
                    new FieldViolation("endpointUrl", "must be a valid URI: " + ex.getMessage()));
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new ValidationException(
                    "endpointUrl must use the http or https scheme",
                    new FieldViolation("endpointUrl", "scheme must be http or https, got: " + scheme));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ValidationException(
                    "endpointUrl must include a host", new FieldViolation("endpointUrl", "must include a host"));
        }
    }
}
