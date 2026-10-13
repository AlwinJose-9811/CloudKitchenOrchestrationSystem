package com.cloudkitchen.service;

import com.cloudkitchen.dto.StationView;
import java.util.List;

/**
 * What the web layer may do with the station workers (FR-WEB-10, FR-CON-11): read their state, pause, resume and restart
 * one. The workers belong to the pipeline; the web packages see only this interface, so they never import {@code pipeline}
 * (SDD 2.2, {@code ArchitectureRulesTest}).
 */
public interface WorkerControl {

    /** One view per station, taken now. */
    List<StationView> stationViews();

    /** {@code true} if {@code code} is the code of a station of this application. */
    boolean hasStation(String code);

    /** Stops the worker of the station from taking new orders; orders in its queue stay queued. */
    void pause(String code);

    /** Lets a paused worker take orders again. */
    void resume(String code);

    /**
     * The administrator's Restart (FR-CON-11, SDD 7.11.7): replaces the worker of the station if {@code expectedGeneration}
     * is still the current one (D-079), also when the station is {@code DEAD}, and gives it a fresh restart budget.
     *
     * @return {@code true} if a new generation was started; {@code false} if the generation was stale, the station is
     *         unknown, the application is stopping or no worker thread could be started
     */
    boolean operatorRestart(String code, int expectedGeneration, String reason);
}
