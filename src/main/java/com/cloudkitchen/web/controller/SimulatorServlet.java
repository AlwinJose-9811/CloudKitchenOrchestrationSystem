package com.cloudkitchen.web.controller;

import com.cloudkitchen.service.SimulatorControl;
import com.cloudkitchen.web.support.BaseServlet;
import com.cloudkitchen.web.support.BadRequestException;
import com.cloudkitchen.web.support.Views;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * {@code GET} and {@code POST /admin/simulator} (SDD 10.2 routes 16 and 17, FR-WEB-09, ADMIN only): start, stop and change
 * the rate of the order feed simulator. It talks to the simulator only through {@link SimulatorControl}. An action other
 * than {@code start}, {@code stop} and {@code rate} is a 400; a rate that is not a whole number between 1 and the
 * configured maximum, a start while running and a stop while stopped are answered with a flash message and no change.
 */
public class SimulatorServlet extends BaseServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setAttribute("sim", Views.of(services().simulator().status()));
        request.setAttribute("pageTitle", "Feed simulator");
        render(request, response, "admin/simulator");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String action = request.getParameter("action");
        if (!"start".equals(action) && !"stop".equals(action) && !"rate".equals(action)) {
            throw new BadRequestException("action");
        }
        SimulatorControl simulator = services().simulator();
        SimulatorControl.Status before = simulator.status();
        if ("stop".equals(action)) {
            if (simulator.stop()) {
                flashSuccess(request, "The feed simulator has been stopped.");
            } else {
                flashInfo(request, "The feed simulator was not running.");
            }
            redirect(request, response, "/admin/simulator");
            return;
        }
        Integer rate = parseRate(request.getParameter("ratePerMinute"), before.maxRatePerMinute());
        if (rate == null) {
            flashError(request, "The rate must be a whole number of orders per minute between 1 and " + before.maxRatePerMinute() + ".");
            redirect(request, response, "/admin/simulator");
            return;
        }
        if ("start".equals(action)) {
            if (simulator.start(rate)) {
                flashSuccess(request, "The feed simulator has been started at " + rate + " orders per minute.");
            } else {
                flashInfo(request, "The feed simulator is already running; use \"Change rate\" to change its speed.");
            }
        } else {
            simulator.setRate(rate);
            flashSuccess(request, "The rate is now " + rate + " orders per minute" + (before.running() ? "." : "; it applies when the simulator is started."));
        }
        redirect(request, response, "/admin/simulator");
    }

    /** The rate if the text is a whole number in range, otherwise {@code null}. */
    private static Integer parseRate(String text, int max) {
        // ASCII digits only: Integer.parseInt would also accept digits of other scripts
        if (text == null || !text.strip().matches("[0-9]{1,9}")) {
            return null;
        }
        int value = Integer.parseInt(text.strip());
        return value >= 1 && value <= max ? value : null;
    }
}
