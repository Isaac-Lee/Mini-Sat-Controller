package msc.services.groundoperations;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StationAllocationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  JdbcTemplate db;
  TransactionTemplate tx;

  @BeforeEach
  void setup() {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    db = new JdbcTemplate(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    db.execute("TRUNCATE station_allocation,booking_dispatch");
  }

  private void insert(String id, BigDecimal start, BigDecimal end) {
    db.update(
        "INSERT INTO station_allocation(booking_id,station_id,start_tai,end_tai)"
            + " VALUES(?,'station',?,?)",
        id,
        start,
        end);
  }

  @Test
  void nanosecondAdjacencyDoesNotOverlapAndCancellationReleasesTheExactRange() {
    var start = new BigDecimal("9007199254740992.000000001");
    var end = start.add(new BigDecimal("0.000000010"));
    insert("a", start, end);
    insert("b", end, end.add(new BigDecimal("0.000000010")));
    assertThrows(
        DataAccessException.class,
        () -> insert("overlap", start.add(new BigDecimal("0.000000001")), end));
    db.update("UPDATE station_allocation SET active=false WHERE booking_id='a'");
    insert("replacement", start, end);
    assertEquals(
        2,
        db.queryForObject("SELECT count(*) FROM station_allocation WHERE active", Integer.class));
  }

  @Test
  void exclusionConstraintProtectsAgainstConcurrentWritersWithoutApplicationLocks()
      throws Exception {
    var start = new BigDecimal("100.000000001");
    var end = new BigDecimal("110.000000001");
    var barrier = new CyclicBarrier(2);
    var winners = new AtomicInteger();
    var conflicts = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs =
          List.of(
              pool.submit(
                  () -> {
                    barrier.await();
                    try {
                      tx.execute(
                          status -> {
                            insert("a", start, end);
                            return null;
                          });
                      winners.incrementAndGet();
                    } catch (DataAccessException expected) {
                      conflicts.incrementAndGet();
                    }
                    return null;
                  }),
              pool.submit(
                  () -> {
                    barrier.await();
                    try {
                      tx.execute(
                          status -> {
                            insert("b", start, end);
                            return null;
                          });
                      winners.incrementAndGet();
                    } catch (DataAccessException expected) {
                      conflicts.incrementAndGet();
                    }
                    return null;
                  }));
      for (var job : jobs) job.get(15, TimeUnit.SECONDS);
    }
    assertEquals(1, winners.get());
    assertEquals(1, conflicts.get());
    assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM station_allocation WHERE active", Integer.class));
  }
}
