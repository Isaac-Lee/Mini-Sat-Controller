package msc.services.referencedata;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import msc.domain.flightdynamics.MeanElements;
import msc.platform.Json;
import org.springframework.stereotype.Component;

/** Fixed provider URL, bounded response, no redirects or automatic retries. */
@Component
public class CelestrakClient {
  private final Json json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public CelestrakClient(Json json) {
    this.json = json;
  }

  public static String url(int noradId) {
    if (noradId < 1 || noradId > 999999999) throw new IllegalArgumentException("Invalid NORAD ID");
    return "https://celestrak.org/NORAD/elements/gp.php?CATNR=" + noradId + "&FORMAT=JSON";
  }

  public String fetch(int noradId) throws IOException, InterruptedException {
    // ofByteArray is bounded by a subscriber below; request timeout covers the complete body.
    var request =
        HttpRequest.newBuilder(URI.create(url(noradId)))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mini-Sat-Controller/0.1 (public-orbit-reference)")
            .GET()
            .build();
    var pending = http.sendAsync(request, info -> new LimitedBody());
    HttpResponse<byte[]> response;
    try {
      response = pending.get(25, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException error) {
      pending.cancel(true);
      throw new IOException("CelesTrak request failed or exceeded total deadline", error);
    } catch (InterruptedException error) {
      pending.cancel(true);
      throw error;
    }
    if (response.statusCode() != 200)
      throw new IOException(
          "CelesTrak HTTP " + response.statusCode() + "; collection paused for operator review");
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final java.util.concurrent.CompletableFuture<byte[]> result =
        new java.util.concurrent.CompletableFuture<>();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    java.util.concurrent.Flow.Subscription subscription;

    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    public void onNext(java.util.List<java.nio.ByteBuffer> items) {
      for (var item : items) {
        if (bytes.size() + item.remaining() > 65536) {
          subscription.cancel();
          result.completeExceptionally(new IOException("GP response exceeds 64 KiB"));
          return;
        }
        byte[] chunk = new byte[item.remaining()];
        item.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }

  public MeanElements parse(int expected, String raw) {
    var array = json.read(raw, JsonNode.class);
    if (!array.isArray() || array.size() != 1)
      throw new IllegalArgumentException("Expected exactly one GP record");
    var n = array.get(0);
    for (String field :
        new String[] {
          "NORAD_CAT_ID",
          "EPOCH",
          "MEAN_MOTION",
          "ECCENTRICITY",
          "INCLINATION",
          "RA_OF_ASC_NODE",
          "ARG_OF_PERICENTER",
          "MEAN_ANOMALY",
          "BSTAR",
          "MEAN_MOTION_DOT",
          "MEAN_MOTION_DDOT",
          "ELEMENT_SET_NO",
          "REV_AT_EPOCH",
          "EPHEMERIS_TYPE",
          "CLASSIFICATION_TYPE"
        })
      if (!n.hasNonNull(field)) throw new IllegalArgumentException("Missing GP field: " + field);
    for (String field :
        new String[] {"CENTER_NAME", "REF_FRAME", "TIME_SYSTEM", "MEAN_ELEMENT_THEORY"}) {
      String value =
          switch (field) {
            case "CENTER_NAME" -> "EARTH";
            case "REF_FRAME" -> "TEME";
            case "TIME_SYSTEM" -> "UTC";
            default -> "SGP4";
          };
      if (n.hasNonNull(field) && !n.get(field).asText().equals(value))
        throw new IllegalArgumentException("Unsupported GP " + field);
    }
    if (integer(n, "NORAD_CAT_ID") != expected || integer(n, "EPHEMERIS_TYPE") != 0)
      throw new IllegalArgumentException("Unexpected NORAD ID or GP theory");
    try {
      java.time.LocalDateTime.parse(n.get("EPOCH").asText().replace("Z", ""));
    } catch (java.time.DateTimeException error) {
      throw new IllegalArgumentException("Invalid GP UTC epoch", error);
    }
    return new MeanElements(
        expected,
        n.path("OBJECT_NAME").asText("NORAD-" + expected),
        n.path("OBJECT_ID").asText(""),
        n.get("EPOCH").asText(),
        number(n, "MEAN_MOTION"),
        number(n, "ECCENTRICITY"),
        number(n, "INCLINATION"),
        number(n, "RA_OF_ASC_NODE"),
        number(n, "ARG_OF_PERICENTER"),
        number(n, "MEAN_ANOMALY"),
        number(n, "BSTAR"),
        number(n, "MEAN_MOTION_DOT"),
        number(n, "MEAN_MOTION_DDOT"),
        integer(n, "ELEMENT_SET_NO"),
        integer(n, "REV_AT_EPOCH"),
        n.get("CLASSIFICATION_TYPE").asText());
  }

  private static double number(JsonNode n, String field) {
    if (!n.get(field).isNumber())
      throw new IllegalArgumentException("Non-numeric GP field: " + field);
    return n.get(field).doubleValue();
  }

  private static int integer(JsonNode n, String field) {
    if (!n.get(field).isIntegralNumber() || !n.get(field).canConvertToInt())
      throw new IllegalArgumentException("Invalid integer GP field: " + field);
    return n.get(field).intValue();
  }
}
