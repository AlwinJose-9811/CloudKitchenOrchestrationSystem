package com.cloudkitchen.web.support;

/** A request parameter is missing or malformed; {@link BaseServlet} turns it into the generic 400 page. */
public final class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String parameter) {
        super("Bad request parameter: " + parameter);
    }
}
