package com.northstar.audit.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DeletionIntentResolver {
    private static final Pattern NEGATED_REQUEST = Pattern.compile(
        "\\b(?:not|never)\\s+(?:asking|requesting|seeking)\\s+(?:for\\s+)?(?:account\\s+|personal[- ]data\\s+|data\\s+)?(?:deletion|erasure|erasing)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPLICITLY_RETAIN = Pattern.compile(
        "\\b(?:do\\s+not|don't|dont|never)\\s+(?:delete|erase|remove|close)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DELETION_REQUEST = Pattern.compile(
        "\\b(?:delete|deletion|erase|erasure|remove|removal|close\\s+(?:my|the)\\s+account|right\\s+to\\s+be\\s+forgotten)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private DeletionIntentResolver() {
    }

    public static Intent resolve(String requestText) {
        String text = requestText == null ? "" : requestText.toLowerCase(Locale.ROOT);
        if (NEGATED_REQUEST.matcher(text).find() || EXPLICITLY_RETAIN.matcher(text).find()) {
            return new Intent("NOT_REQUESTED", "The request explicitly says not to delete or asks only for another service.");
        }
        if (DELETION_REQUEST.matcher(text).find()) {
            return new Intent("REQUESTED", "The request text explicitly mentions deletion, erasure, removal, or account closure.");
        }
        return new Intent("REVIEW_REQUIRED", "The request text does not clearly establish whether deletion was requested.");
    }

    public record Intent(String status, String explanation) {
    }
}
