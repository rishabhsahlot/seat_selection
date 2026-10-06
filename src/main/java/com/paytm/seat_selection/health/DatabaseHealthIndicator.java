package com.paytm.seat_selection.health;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Readiness check that the database is reachable, exposed as the {@code database} health
 * component. It opens its own short-lived connection with tight timeouts instead of
 * borrowing from the pool, so:
 * <ul>
 * <li>when Postgres is down, readiness fails closed within seconds (the pool would wait
 * out its 60s connection timeout);</li>
 * <li>when the pool is merely saturated by a burst, readiness stays UP and the platform
 * does not pull or restart a healthy instance.</li>
 * </ul>
 */
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

	private final DataSourceProperties dataSource;

	public DatabaseHealthIndicator(DataSourceProperties dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public Health health() {
		Properties props = new Properties();
		props.setProperty("user", this.dataSource.determineUsername());
		props.setProperty("password", this.dataSource.determinePassword());
		props.setProperty("connectTimeout", "2");
		props.setProperty("socketTimeout", "3");
		props.setProperty("loginTimeout", "3");
		long start = System.nanoTime();
		try (Connection connection = DriverManager.getConnection(this.dataSource.determineUrl(), props);
				Statement statement = connection.createStatement()) {
			statement.execute("SELECT 1");
			return Health.up()
				.withDetail("database", connection.getMetaData().getDatabaseProductName())
				.withDetail("latency_ms", (System.nanoTime() - start) / 1_000_000)
				.build();
		}
		catch (SQLException ex) {
			return Health.down().withDetail("error", ex.getMessage()).build();
		}
	}

}
