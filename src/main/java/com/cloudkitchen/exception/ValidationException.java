package com.cloudkitchen.exception;

import com.cloudkitchen.dto.FieldError;
import java.util.List;

/** An order request broke one or more validation rules; carries every violation found (VR-14). */
public class ValidationException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    public ValidationException(List<FieldError> errors) {
        super("VALIDATION", "The order request is not valid (" + (errors == null ? 0 : errors.size()) + " problem(s))");
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public List<FieldError> getErrors() {
        return errors;
    }
}
