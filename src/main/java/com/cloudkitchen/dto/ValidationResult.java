package com.cloudkitchen.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The outcome of validating a request: every violation found, or none. */
public record ValidationResult(List<FieldError> errors) {

    private static final ValidationResult VALID = new ValidationResult(List.of());

    public ValidationResult {
        errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public static ValidationResult valid() {
        return VALID;
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    /** A new result holding the errors of both, this one's first. */
    public ValidationResult merge(ValidationResult other) {
        List<FieldError> all = new ArrayList<>(errors);
        all.addAll(other.errors);
        return new ValidationResult(all);
    }
}
