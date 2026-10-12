package com.cloudkitchen.web.controller;

import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.web.support.BaseServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /admin/snapshot} (SDD 10.2 route 20, FR-WEB-13, ADMIN only): writes a signed snapshot of the in-flight orders
 * now. The answer names the file, never its directory; a failure is reported without internal detail. There is no
 * {@code GET}: the button is on the worker panel.
 */
public class SnapshotServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotServlet.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            String file = services().snapshots().writeNow();
            LOG.info("Snapshot {} written on request of user {}", file, currentUser(request).userId());
            flashSuccess(request, "A snapshot was written: " + file + ".");
        } catch (CloudKitchenException e) {
            LOG.warn("The snapshot on demand failed ({})", e.getCode());
            flashError(request, "The snapshot could not be written. The database and the last periodic snapshot remain the recovery sources.");
        }
        redirect(request, response, "/admin/workers");
    }
}
