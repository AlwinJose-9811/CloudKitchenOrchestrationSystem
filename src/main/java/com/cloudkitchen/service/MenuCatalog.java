package com.cloudkitchen.service;

import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.Station;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable lookup of the stations and menu items a submission is checked against. It is built once per submission
 * from what the database returned, so validation and pricing see the same data and need no I/O.
 */
public final class MenuCatalog {

    private final Map<Long, MenuItem> itemsById;
    private final Map<String, Station> stationsByCode;
    private final Map<Long, Station> stationsById;

    private MenuCatalog(Map<Long, MenuItem> itemsById, Map<String, Station> stationsByCode,
                        Map<Long, Station> stationsById) {
        this.itemsById = Collections.unmodifiableMap(itemsById);
        this.stationsByCode = Collections.unmodifiableMap(stationsByCode);
        this.stationsById = Collections.unmodifiableMap(stationsById);
    }

    /** @throws IllegalArgumentException if two stations share a code or id, or two items share an id */
    public static MenuCatalog of(Collection<Station> stations, Collection<MenuItem> items) {
        Objects.requireNonNull(stations, "stations");
        Objects.requireNonNull(items, "items");
        Map<String, Station> byCode = new HashMap<>();
        Map<Long, Station> byId = new HashMap<>();
        for (Station station : stations) {
            if (byCode.put(station.getCode(), station) != null || byId.put(station.getId(), station) != null) {
                throw new IllegalArgumentException("duplicate station " + station.getCode());
            }
        }
        Map<Long, MenuItem> itemMap = new HashMap<>();
        for (MenuItem item : items) {
            if (itemMap.put(item.getId(), item) != null) {
                throw new IllegalArgumentException("duplicate menu item " + item.getId());
            }
        }
        return new MenuCatalog(itemMap, byCode, byId);
    }

    public Optional<MenuItem> item(long itemId) {
        return Optional.ofNullable(itemsById.get(itemId));
    }

    /** Looks a station up by its (upper-case) brand code; includes inactive stations. */
    public Optional<Station> station(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(stationsByCode.get(code));
    }

    public Optional<Station> stationById(long stationId) {
        return Optional.ofNullable(stationsById.get(stationId));
    }
}
