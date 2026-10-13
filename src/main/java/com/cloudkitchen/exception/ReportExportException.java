package com.cloudkitchen.exception;

/** A daily CSV report could not be written. No partial final file is left behind; the cause is kept. */
public class ReportExportException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    public ReportExportException(String message, Throwable cause) {
        super("REPORT_EXPORT", message, cause);
    }
}
