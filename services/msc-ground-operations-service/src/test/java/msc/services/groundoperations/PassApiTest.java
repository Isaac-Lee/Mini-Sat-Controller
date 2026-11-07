package msc.services.groundoperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import msc.contracts.GroundContracts.*;
import msc.domain.groundoperations.GroundOperations.*;
import msc.domain.time.*;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PassApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final AtomicLong now = new AtomicLong(150);
  final org.springframework.security.core.Authentication actor =
      new UsernamePasswordAuthenticationToken("operator", "unused");
  final Reservation reservation =
      new Reservation(
          "station",
          1,
          "sat-1",
          new TimeWindow(MissionInstant.tai(100), MissionInstant.tai(200)),
          "prediction",
          12.5);
  StateStore store;
  Json json;
  ServiceHttp http;
  PassApi api;
  GroundApi ground;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    var db = new JdbcTemplate(ds);
    db.execute(
        "TRUNCATE state_head,state_history,outbox,idempotency,station_allocation,booking_dispatch");
    json = new Json(JsonMapper.builder().findAndAddModules().build());
    msc.ports.Clock clock = () -> MissionInstant.tai(now.get());
    store =
        new StateStore(
            db, new TransactionTemplate(new DataSourceTransactionManager(ds)), json, clock);
    http = mock(ServiceHttp.class);
    api = new PassApi(store, new SimulatedGroundStation(store, http, json, clock));
    ground = new GroundApi(store, db, http, clock);
  }

  Booking booking(String id, BookingStatus status) {
    return new Booking(id, reservation, status, "simulator-receipt:" + id, "seeded");
  }

  void seed(String id, BookingStatus status, BookingStatus provider) {
    store.transaction(() -> store.create("booking", id, booking(id, status)));
    when(http.get(eq("simulator"), eq("/internal/station-bookings/" + id), eq(Booking.class)))
        .thenReturn(booking(id, provider));
  }

  PassRecord begin(String id, String key) {
    return json.convert(api.begin(id, key, actor).get("body"), PassRecord.class);
  }

  PassRecord report(String passId, String bookingId, String key) {
    return json.convert(
        api.report(passId, new PassApi.ReportRequest(bookingId), key, actor).get("body"),
        PassRecord.class);
  }

  static HttpStatus status(Runnable call) {
    return assertThrows(ApiException.class, call::run).status();
  }

  @Test
  void beginThenReportIsIdempotentAndLabelledSimulation() {
    seed("b1", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    var active = begin("b1", "k1");
    assertEquals(PassStatus.ACTIVE, active.status());
    assertEquals("SIMULATION", active.environment());
    assertEquals(MissionInstant.tai(150), active.contactStart());
    now.set(160);
    assertEquals(active, begin("b1", "k2"), "duplicate begin returns the same session");
    assertEquals(HttpStatus.CONFLICT, status(() -> report("pass-b1", "b1", "r1")));
    now.set(200);
    var done = report("pass-b1", "b1", "r2");
    assertEquals(PassStatus.REPORTED, done.status());
    assertEquals(PassOutcome.COMPLETED, done.outcome());
    assertEquals(MissionInstant.tai(200), done.contactEnd());
    assertEquals(12_500_000, done.accountedBytes());
    now.set(500);
    assertEquals(done, report("pass-b1", "b1", "r3"), "duplicate report keeps the first report");
    assertEquals(done, api.pass("pass-b1"));
    assertEquals(
        2, store.history("pass", "pass-b1").size(), "duplicates write no extra revisions");
  }

  @Test
  void illegalTransitionsAndStaleRequestsAreRejected() {
    seed("tentative", BookingStatus.REQUESTING, BookingStatus.CONFIRMED);
    assertEquals(HttpStatus.CONFLICT, status(() -> begin("tentative", "a")));
    seed("cancelled", BookingStatus.CANCEL_PENDING, BookingStatus.CONFIRMED);
    assertEquals(HttpStatus.CONFLICT, status(() -> begin("cancelled", "b")));
    seed("lost", BookingStatus.CONFIRMED, BookingStatus.CANCELLED);
    assertEquals(HttpStatus.CONFLICT, status(() -> begin("lost", "c")));
    seed("early", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    now.set(99);
    assertEquals(HttpStatus.CONFLICT, status(() -> begin("early", "d")));
    now.set(200);
    assertEquals(HttpStatus.CONFLICT, status(() -> begin("early", "e")), "window passed");
    assertEquals(HttpStatus.NOT_FOUND, status(() -> report("pass-early", "early", "f")));
    assertTrue(store.find("pass", "pass-early", PassRecord.class).isEmpty());

    now.set(150);
    seed("b2", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    begin("b2", "g");
    now.set(250);
    assertEquals(
        HttpStatus.CONFLICT, status(() -> report("pass-b2", "other", "h")), "stale handle");
    assertEquals(PassStatus.ACTIVE, api.pass("pass-b2").status());
  }

  @Test
  void cancellationDuringPassReportsBookingLostWithoutBytes() {
    seed("b3", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    begin("b3", "k");
    store.transaction(
        () ->
            store.update(
                "booking",
                "b3",
                store.require("booking", "b3", Booking.class).version(),
                booking("b3", BookingStatus.CANCEL_PENDING)));
    now.set(201);
    var lost = report("pass-b3", "b3", "r");
    assertEquals(PassOutcome.BOOKING_LOST, lost.outcome());
    assertEquals(0, lost.accountedBytes());
    assertNull(lost.contactEnd());
  }

  @Test
  void providerOutageLeavesSessionActiveAndRetrySucceeds() {
    seed("b4", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    begin("b4", "k");
    now.set(210);
    when(http.get(eq("simulator"), eq("/internal/station-bookings/b4"), eq(Booking.class)))
        .thenThrow(new ResourceAccessException("down"))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "gone", null, new byte[0], StandardCharsets.UTF_8));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, status(() -> report("pass-b4", "b4", "r1")));
    assertEquals(PassStatus.ACTIVE, api.pass("pass-b4").status());
    assertEquals(PassOutcome.BOOKING_LOST, report("pass-b4", "b4", "r2").outcome());
  }

  @Test
  void portRejectsOpportunityThatDoesNotMatchTheBooking() {
    seed("b5", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    var port = new SimulatedGroundStation(store, http, json, () -> MissionInstant.tai(150));
    var mismatched =
        new ContactOpportunity(
            "b5",
            new msc.domain.shared.Ids.SpacecraftId("sat-2"),
            "station",
            reservation.window());
    assertEquals(HttpStatus.CONFLICT, status(() -> port.book(mismatched)));
    assertEquals(
        HttpStatus.CONFLICT,
        status(() -> port.begin(new StationBooking("b5", "b5", BookingState.TENTATIVE))));
    assertEquals(12_500_001, SimulatedGroundStation.bytes(12.5000005));
  }

  @Test
  void cancelIsGuardedAfterTheWindowAndRetriedBeginSurvivesCancel() {
    seed("b6", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    var active = begin("b6", "k1");
    ground.cancel("b6", "c1", actor);
    assertEquals(active, begin("b6", "k2"), "retried begin after cancel keeps the session");

    seed("b7", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    begin("b7", "k3");
    now.set(200);
    assertEquals(HttpStatus.CONFLICT, status(() -> ground.cancel("b7", "c2", actor)));
    assertEquals(PassOutcome.COMPLETED, report("pass-b7", "b7", "r").outcome());
    ground.cancel("b7", "c3", actor);
    assertEquals(PassOutcome.COMPLETED, api.pass("pass-b7").outcome());
  }

  @Test
  void concurrentReportsWriteOneRevision() throws Exception {
    seed("b8", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    begin("b8", "k");
    now.set(200);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(http.get(eq("simulator"), eq("/internal/station-bookings/b8"), eq(Booking.class)))
        .thenAnswer(
            call -> {
              entered.countDown();
              release.await(10, TimeUnit.SECONDS);
              return booking("b8", BookingStatus.CONFIRMED);
            })
        .thenReturn(booking("b8", BookingStatus.CONFIRMED));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var slow = executor.submit(() -> report("pass-b8", "b8", "slow"));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var fast = report("pass-b8", "b8", "fast");
      release.countDown();
      assertEquals(fast, slow.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(2, store.history("pass", "pass-b8").size());
  }

  @Test
  void providerMismatchAndIdempotencyKeyReuseAreRejected() {
    seed("b9", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    begin("b9", "k");
    now.set(200);
    when(http.get(eq("simulator"), eq("/internal/station-bookings/b9"), eq(Booking.class)))
        .thenReturn(booking("other", BookingStatus.CONFIRMED));
    assertEquals(HttpStatus.BAD_GATEWAY, status(() -> report("pass-b9", "b9", "r")));
    assertEquals(PassStatus.ACTIVE, api.pass("pass-b9").status());

    seed("b10", BookingStatus.CONFIRMED, BookingStatus.CONFIRMED);
    assertEquals(HttpStatus.CONFLICT, status(() -> begin("b10", "k")), "key reused on b10");
  }
}
