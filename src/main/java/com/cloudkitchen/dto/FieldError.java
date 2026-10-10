package com.cloudkitchen.dto;

import java.util.Objects;

/** One validation problem: which field, a stable error code, and a message that is safe to show to the user. */
public record FieldError(String field, String code, String message) {

    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
