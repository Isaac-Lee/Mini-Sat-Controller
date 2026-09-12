package msc.platform;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class ServiceHttpDownloadTest {
  HttpServer server;
  ServiceHttp http;
  AtomicReference<String> authorization = new AtomicReference<>();

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/content",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          boolean chunked = "chunked".equals(exchange.getRequestURI().getQuery());
          exchange.sendResponseHeaders(200, chunked ? 0 : 4);
          try (var output = exchange.getResponseBody()) {
            output.write(new byte[] {1, 2, 3, 4});
          }
        });
    server.createContext(
        "/missing",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    http =
        new ServiceHttp(
            new MockEnvironment()
                .withProperty(
                    "msc.services.simulator.url",
                    "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("msc.security.mode", "local")
                .withProperty("msc.security.local.service-password", "test-only"));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void streamsExactBytesWithServiceAuthentication() {
    var output = new ByteArrayOutputStream();
    assertEquals(4, http.download("simulator", "/content", output, 4));
    assertArrayEquals(new byte[] {1, 2, 3, 4}, output.toByteArray());
    assertEquals("Basic c2VydmljZTp0ZXN0LW9ubHk=", authorization.get());
  }

  @Test
  void boundsDeclaredAndChunkedResponsesAndRejectsErrors() {
    for (String path : new String[] {"/content", "/content?chunked", "/missing"}) {
      var output = new ByteArrayOutputStream();
      assertThrows(ApiException.class, () -> http.download("simulator", path, output, 3));
      assertTrue(output.size() <= 3);
    }
  }

  @Test
  void rejectsUnboundedOrUnknownDestinationsBeforeIO() {
    var output = new ByteArrayOutputStream();
    assertThrows(
        IllegalArgumentException.class, () -> http.download("unknown", "/content", output, 4));
    assertThrows(
        IllegalArgumentException.class, () -> http.download("simulator", "//other", output, 4));
    assertThrows(
        IllegalArgumentException.class, () -> http.download("simulator", "/content", output, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> http.download("simulator", "/content", output, 67108865));
    assertNull(authorization.get());
  }
}
