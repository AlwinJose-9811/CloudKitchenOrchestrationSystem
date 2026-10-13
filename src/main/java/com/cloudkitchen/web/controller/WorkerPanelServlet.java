package com.cloudkitchen.web.controller;

import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.service.WorkerControl;
import com.cloudkitchen.web.support.BadRequestException;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.FormInput;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code GET} and {@code POST /admin/workers} (SDD 10.2 routes 18 and 19, FR-WEB-10, FR-CON-11, ADMIN only): the worker panel.
 * It reaches the workers only through {@link WorkerControl}. {@code restart} carries the generation the page showed (D-079) and also brings a {@code DEAD} worker back (D-095):
 * a missing, malformed or no longer current generation restarts nothing and says so, so a stale click can never kill a
 * healthy replacement. An action other than {@code pause}, {@code resume} and {@code restart} is a 400.
 */
public class WorkerPanelServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(WorkerPanelServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        List<StationView> stations = services().workers().stationViews().stream().sorted(Comparator.comparing(StationView::stationCode)).toList();
        request.setAttribute("stations", Views.of(stations));
        request.setAttribute("pageTitle", "Workers");
        render(request, response, "admin/workers");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        if (!"pause".equals(action) && !"resume".equals(action) && !"restart".equals(action)) {
            throw new BadRequestException("action");
        }
        WorkerControl workers = services().workers();
        String code = request.getParameter("stationCode");
        if (!workers.hasStation(code)) {
            flashError(request, "This kitchen station does not exist. Nothing was changed.");
            redirect(request, response, "/admin/workers");
            return;
        }
        long userId = currentUser(request).userId();
        switch (action) {
            case "pause" -> {
                workers.pause(code);
                LOG.info("Worker of station {} paused by user {}", code, userId);
                flashSuccess(request, "Pause requested: the worker of " + code + " takes no new order after the one it is working on. Its queue keeps filling and is worked off after Resume.");
            }
            case "resume" -> {
                workers.resume(code);
                LOG.info("Worker of station {} resumed by user {}", code, userId);
                flashSuccess(request, "The worker of " + code + " is running again.");
            }
            default -> restart(request, workers, code, userId);
        }
        redirect(request, response, "/admin/workers");
    }

    private void restart(HttpServletRequest request, WorkerControl workers, String code, long userId) {
        Integer shown = FormInput.wholeNumber(request.getParameter("generation"));
        if (shown == null || shown < 0) {
            flashError(request, "The request did not say which worker generation the page showed, so nothing was restarted. Reload the page and try again.");
            return;
        }
        boolean wasDead = stationOf(workers, code).filter(s -> s.worker().state() == WorkerState.DEAD).isPresent();
        if (workers.operatorRestart(code, shown, "operator " + userId)) {
            LOG.info("Worker of station {} restarted by user {}{}", code, userId, wasDead ? " (it was DEAD)" : "");
            flashSuccess(request, "The worker of " + code + " was restarted (generation " + (shown + 1) + ")"
                    + (wasDead ? "; it had given up and has a fresh restart budget." : "."));
            return;
        }
        int now = stationOf(workers, code).map(s -> s.worker().generation()).orElse(-1);
        if (now != shown) {
            flashError(request, "The page was out of date: the worker of " + code + " has already been replaced (generation " + now + "). Nothing was restarted.");
        } else {
            flashError(request, "The worker of " + code + " could not be restarted: the application is stopping or no worker thread could be started. Nothing was restarted.");
        }
    }

    private static java.util.Optional<StationView> stationOf(WorkerControl workers, String code) {
        return workers.stationViews().stream().filter(s -> s.stationCode().equals(code)).findFirst();
    }
}
