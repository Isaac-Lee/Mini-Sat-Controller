package msc.services.referencedata;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import msc.platform.Json;
import org.junit.jupiter.api.Test;

class CelestrakClientTest {
  final CelestrakClient client = new CelestrakClient(new Json(JsonMapper.builder().build()));

  static String fixture() throws Exception {
    try (var input = CelestrakClientTest.class.getResourceAsStream("/spaceeye-t1-gp.json")) {
      return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void validatesLiveRecordedSpaceeyeReferenceAndSupportsLongCatalogNumbers() throws Exception {
    var elements = client.parse(63229, fixture());
    assertEquals("SPACEEYE-T1", elements.name());
    assertEquals("2026-09-11T03:28:43.405248", elements.epochUtc());
    assertEquals(15.23132288, elements.meanMotionRevolutionsPerDay());
    assertEquals(
        123456789, client.parse(123456789, fixture().replace("63229", "123456789")).noradId());
  }

  @Test
  void rejectsWrongIdentityMissingFieldsUnsupportedTheoryAndMalformedData() throws Exception {
    String raw = fixture();
    for (String bad :
        new String[] {
          "[]",
          "No GP data found",
          "<html>error</html>",
          raw.replace("63229", "63230"),
          raw.replace("\"BSTAR\"", "\"missing\""),
          raw.replace("15.23132288", "\"oops\""),
          raw.replace("0.00040966", "1.5"),
          raw.replace("\"EPHEMERIS_TYPE\":0", "\"EPHEMERIS_TYPE\":4"),
          raw.replace("03:28:43", "99:28:43"),
          raw.replace("\"EPOCH\"", "\"REF_FRAME\":\"GCRF\",\"EPOCH\"")
        }) assertThrows(IllegalArgumentException.class, () -> client.parse(63229, bad), bad);
    assertThrows(IllegalArgumentException.class, () -> CelestrakClient.url(-1));
  }

  @Test
  void capsStreamingResponseBeforeAllocatingUnboundedMemory() {
    var body = new CelestrakClient.LimitedBody();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    body.onSubscribe(
        new java.util.concurrent.Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled.set(true);
          }
        });
    body.onNext(java.util.List.of(java.nio.ByteBuffer.allocate(65537)));
    assertTrue(cancelled.get());
    assertTrue(body.result.isCompletedExceptionally());
  }
}
