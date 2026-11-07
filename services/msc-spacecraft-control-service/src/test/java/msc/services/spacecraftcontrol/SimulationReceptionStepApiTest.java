package msc.services.spacecraftcontrol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import msc.domain.time.MissionInstant;
import msc.platform.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SimulationReceptionStepApiTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  final Json json = new CommandCompilerTest().json;
  final UUID scenario = UUID.randomUUID();
  final String path = "/internal/simulation/scenarios/" + scenario + "/loads/load-1/receive";
  final org.springframework.security.authentication.UsernamePasswordAuthenticationToken op1 =
      new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator1", "x");
  final org.springframework.security.authentication.UsernamePasswordAuthenticationToken op2 =
      new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator2", "x");
  StateStore store;
  ServiceHttp http;
  SimulationReceptionStepApi api;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    var db = new JdbcTemplate(ds);
    db.execute("TRUNCATE state_head,state_history,outbox,idempotency");
    store =
        new StateStore(
            db,
            new TransactionTemplate(new DataSourceTransactionManager(ds)),
            json,
            () -> MissionInstant.tai(1));
    http = mock(ServiceHttp.class);
    api = new SimulationReceptionStepApi(store, http);
  }

  static String key(String actor, String clientKey) {
    return "control-v1:recv:" + SimulationReceptionStepApi.sha256("load-1\n" + actor + "\n" + clientKey);
  }

  void release(String environment) {
    store.transaction(
        () ->
            store.create(
                SimulationDispatchApi.RELEASE,
                "load-1",
                Map.of("environment", environment, "scenarioId", scenario.toString())));
  }

  @Test
  void relaysOneAttemptWithAPerAttemptKeyAndReturnsTheSimulatorBeliefUnchanged() {
    release("SIMULATION");
    var unknown = json.tree(Map.of("belief", "UNKNOWN", "reason", "DELAYED"));
    when(http.post(eq("simulator"), eq(path), any(), anyString(), eq(JsonNode.class)))
        .thenReturn(unknown);
    var attempt = new SimulationReceptionStepApi.Attempt(SimulationReceptionStepApi.Channel.ACKNOWLEDGMENT);
    assertEquals(unknown, api.receive("load-1", attempt, "k1", op1));
    api.receive("load-1", attempt, "k2", op1);
    api.receive("load-1", attempt, "k1", op2);
    verify(http)
        .post(
            "simulator",
            path,
            Map.of("channel", SimulationReceptionStepApi.Channel.ACKNOWLEDGMENT),
            key("operator1", "k1"),
            JsonNode.class);
    verify(http).post(eq("simulator"), eq(path), any(), eq(key("operator1", "k2")), eq(JsonNode.class));
    verify(http).post(eq("simulator"), eq(path), any(), eq(key("operator2", "k1")), eq(JsonNode.class));
    assertNotEquals(key("operator1", "k1"), key("operator2", "k1"));
    assertTrue(key("operator1", "k".repeat(100)).length() <= 160);
  }

  @Test
  void refusesMissingAndNonSimulationReleasesWithoutCallingTheSimulator() {
    var attempt = new SimulationReceptionStepApi.Attempt(SimulationReceptionStepApi.Channel.RECONCILIATION);
    var missing = assertThrows(ApiException.class, () -> api.receive("load-1", attempt, "k", op1));
    assertEquals(HttpStatus.NOT_FOUND, missing.status());
    release("HARDWARE");
    var hardware = assertThrows(ApiException.class, () -> api.receive("load-1", attempt, "k", op1));
    assertEquals(HttpStatus.CONFLICT, hardware.status());
    verifyNoInteractions(http);
  }

  @Test
  void mapsSimulatorRejectionAndOutage() {
    release("SIMULATION");
    var attempt = new SimulationReceptionStepApi.Attempt(SimulationReceptionStepApi.Channel.ACKNOWLEDGMENT);
    when(http.post(eq("simulator"), eq(path), any(), eq(key("operator1", "a")), any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "x", null, null, null));
    when(http.post(eq("simulator"), eq(path), any(), eq(key("operator1", "b")), any()))
        .thenThrow(new ResourceAccessException("down"));
    when(http.post(eq("simulator"), eq(path), any(), eq(key("operator1", "c")), any()))
        .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "x", null, null, null));
    assertEquals(
        HttpStatus.BAD_GATEWAY,
        assertThrows(ApiException.class, () -> api.receive("load-1", attempt, "c", op1)).status());
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ApiException.class, () -> api.receive("load-1", attempt, "a", op1)).status());
    assertEquals(
        HttpStatus.SERVICE_UNAVAILABLE,
        assertThrows(ApiException.class, () -> api.receive("load-1", attempt, "b", op1)).status());
  }
}
