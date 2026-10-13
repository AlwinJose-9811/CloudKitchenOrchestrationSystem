package com.cloudkitchen.service;

import com.cloudkitchen.domain.Station;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Maps a brand code to the kitchen station that cooks it (one station per brand). Only active stations are routable.
 * The map is replaced as a whole ({@link #refresh}), never edited, so readers on other threads always see a complete,
 * consistent view without locking.
 */
public final class RoutingService {

    private volatile Map<String, Station> byBrand = Map.of();

    public RoutingService() {
    }

    public RoutingService(Collection<Station> stations) {
        refresh(stations);
    }

    /** The active station for this brand code (exact, upper-case), or empty for an unknown or inactive brand. */
    public Optional<Station> stationFor(String brandCode) {
        return brandCode == null ? Optional.empty() : Optional.ofNullable(byBrand.get(brandCode));
    }

    /** The active stations, ordered by id. */
    public List<Station> stations() {
        return byBrand.values().stream().sorted(Comparator.comparingLong(Station::getId)).toList();
    }

    /** Replaces the routing table; inactive stations are left out. */
    public void refresh(Collection<Station> stations) {
        Objects.requireNonNull(stations, "stations");
        Map<String, Station> next = new TreeMap<>();
        for (Station station : stations) {
            if (station.isActive() && next.put(station.getCode(), station) != null) {
                throw new IllegalArgumentException("two active stations share the code " + station.getCode());
            }
        }
        byBrand = Map.copyOf(next);
    }
}
