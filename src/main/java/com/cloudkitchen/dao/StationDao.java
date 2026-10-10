package com.cloudkitchen.dao;

import com.cloudkitchen.domain.Station;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Read access to kitchen stations. Implementations never commit or close the connection they are given. */
public interface StationDao {

    List<Station> findAllActive(Connection c) throws SQLException;

    Optional<Station> findByCode(Connection c, String code) throws SQLException;

    Optional<Station> findById(Connection c, long id) throws SQLException;
}
