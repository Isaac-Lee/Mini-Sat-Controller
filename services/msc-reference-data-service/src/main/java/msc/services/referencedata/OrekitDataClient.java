package msc.services.referencedata;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import msc.platform.Json;
import org.springframework.stereotype.Component;

/**
 * Official orekit-data repository (same source as scripts/fetch-orekit-reference.py). Fixed URLs,
 * bounded bodies, no redirects or automatic retries. Integrity is checked by {@link
 * ReferenceArchive}, not here.
 */
@Component
public class OrekitDataClient {
  static final String PROJECT = "https://gitlab.orekit.org/api/v4/projects/orekit%2Forekit-data";
  static final String RAW = "https://gitlab.orekit.org/orekit/orekit-data/-/raw/";
  static final int MAX_FILE_BYTES = 10_000_000;

  /** Downloaded bytes plus the provider's own git blob and SHA-256 claims for that commit. */
  public record Download(String path, String url, byte[] data, String gitBlob, String sha256) {}

  private final Json json;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public OrekitDataClient(Json json) {
    this.json = json;
  }

  public String latestCommit() throws IOException, InterruptedException {
    var body = send(request(PROJECT + "/repository/branches/main").GET().build(), 65536);
    String commit = json.read(new String(body.body(), StandardCharsets.UTF_8), JsonNode.class)
        .path("commit").path("id").asText("");
    if (!commit.matches("[0-9a-f]{40}")) throw new IOException("orekit-data branch has no commit");
    return commit;
  }

  public Download download(String commit, String path) throws IOException, InterruptedException {
    if (!commit.matches("[0-9a-f]{40}") || !ReferenceArchive.FILES.contains(path))
      throw new IllegalArgumentException("Unsupported orekit-data reference");
    var head =
        send(
            request(
                    PROJECT
                        + "/repository/files/"
                        + URLEncoder.encode(path, StandardCharsets.UTF_8)
                        + "?ref="
                        + commit)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            0);
    String url = RAW + commit + "/" + path;
    var raw = send(request(url).GET().build(), MAX_FILE_BYTES);
    return new Download(
        path,
        url,
        raw.body(),
        head.headers().firstValue("X-Gitlab-Blob-Id").orElse(""),
        head.headers().firstValue("X-Gitlab-Content-Sha256").orElse(""));
  }

  private static HttpRequest.Builder request(String url) {
    return HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(120))
        .header("User-Agent", "Mini-Sat-Controller/0.1 (reference-archive)");
  }

  private HttpResponse<byte[]> send(HttpRequest request, int limit)
      throws IOException, InterruptedException {
    var pending = http.sendAsync(request, info -> new CelestrakClient.LimitedBody(limit));
    HttpResponse<byte[]> response;
    try {
      response = pending.get(150, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException error) {
      pending.cancel(true);
      throw new IOException("orekit-data request failed or exceeded total deadline", error);
    } catch (InterruptedException error) {
      pending.cancel(true);
      throw error;
    }
    if (response.statusCode() != 200)
      throw new IOException("orekit-data HTTP " + response.statusCode() + " for " + request.uri());
    return response;
  }
}
