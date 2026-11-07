package msc.services.groundoperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SimulationDownlinkStepApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final Json json = new Json(JsonMapper.builder().findAndAddModules().build());
  final UsernamePasswordAuthenticationToken op1 =
      new UsernamePasswordAuthenticationToken("operator1", "x");
  final UsernamePasswordAuthenticationToken op2 =
      new UsernamePasswordAuthenticationToken("operator2", "x");
  final UUID scenario = UUID.randomUUID();
  StateStore store;
  ServiceHttp http;
  SimulationDownlinkStepApi api;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    var db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,station_allocation,booking_dispatch");
    store =
        new StateStore(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(1));
    http = mock(ServiceHttp.class);
    api = new SimulationDownlinkStepApi(store, http, json);
    store.transaction(() -> store.create("booking", "booking-1", Map.of("id", "booking-1")));
  }

  SimulationDownlinkStepApi.PlanRequest plan() {
    return new SimulationDownlinkStepApi.PlanRequest(scenario, "dl-load", "dl-cmd", "img-cmd");
  }

  @Test
  @SuppressWarnings("unchecked")
  void planDerivesThePayloadIdServerSideAndScopesTheKeyPerOperator() {
    var planned = json.tree(Map.of("id", "plan-1"));
    when(http.post(eq("simulator"), eq("/internal/simulation/downlinks"), any(), anyString(), eq(JsonNode.class)))
        .thenReturn(planned);
    assertEquals(planned, api.plan("booking-1", plan(), "k", op1));
    api.plan("booking-1", plan(), "k", op2);
    var body = ArgumentCaptor.forClass(Object.class);
    var keys = ArgumentCaptor.forClass(String.class);
    verify(http, times(2))
        .post(eq("simulator"), eq("/internal/simulation/downlinks"), body.capture(), keys.capture(), eq(JsonNode.class));
    var sent = (Map<String, Object>) body.getAllValues().get(0);
    assertEquals(json.fingerprint(List.of(scenario.toString(), "img-cmd")), sent.get("payloadId"));
    assertEquals("booking-1", sent.get("bookingId"));
    assertEquals(Set.of("scenarioId", "loadId", "commandId", "payloadId", "bookingId"), sent.keySet());
    assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    assertTrue(keys.getAllValues().get(0).length() <= 160);
  }

  @Test
  void planPassesTheSimulatorReasonThrough() {
    when(http.post(eq("simulator"), eq("/internal/simulation/downlinks"), any(), anyString(), eq(JsonNode.class)))
        .thenThrow(
            HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "x",
                null,
                "{\"code\":\"CONFLICT\",\"message\":\"Payload allocations exceed booking volume\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8));
    var rejected = assertThrows(ApiException.class, () -> api.plan("booking-1", plan(), "k", op1));
    assertEquals(HttpStatus.CONFLICT, rejected.status());
    assertEquals("Simulator: Payload allocations exceed booking volume", rejected.getMessage());
  }

  @Test
  void planRefusesABookingThisServiceDoesNotOwn() {
    var missing =
        assertThrows(ApiException.class, () -> api.plan("other-booking", plan(), "k", op1));
    assertEquals(HttpStatus.NOT_FOUND, missing.status());
    verifyNoInteractions(http);
  }

  @Test
  void receiveRelaysOneAttemptAndMapsSimulatorFailures() {
    var attempt = new SimulationDownlinkStepApi.Attempt(SimulationDownlinkStepApi.Channel.RECONCILIATION);
    var unknown = json.tree(Map.of("belief", "UNKNOWN", "reason", "DISCONNECTED"));
    String path = "/internal/simulation/downlinks/plan-1/receive";
    when(http.post(eq("simulator"), eq(path), any(), anyString(), eq(JsonNode.class)))
        .thenReturn(unknown)
        .thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "x", null, null, null))
        .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "x", null, null, null))
        .thenThrow(new ResourceAccessException("down"));
    assertEquals(unknown, api.receive("plan-1", attempt, "a", op1));
    for (var expected :
        List.of(HttpStatus.CONFLICT, HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE))
      assertEquals(
          expected,
          assertThrows(ApiException.class, () -> api.receive("plan-1", attempt, "b", op1)).status());
    verify(http, times(4))
        .post(eq("simulator"), eq(path), eq(Map.of("channel", SimulationDownlinkStepApi.Channel.RECONCILIATION)), anyString(), eq(JsonNode.class));
  }
}
