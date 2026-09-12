package msc.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** Bounded synchronous adapter; retries of side effects belong to their idempotent workflow. */
@Component
public final class ServiceHttp {
  private static final Set<String> SERVICES =
      Set.of(
          "tasking",
          "planning",
          "flight-dynamics",
          "mission-definition",
          "reference-data",
          "ground-operations",
          "spacecraft-control",
          "space-link",
          "monitoring",
          "anomaly",
          "acquisition",
          "product",
          "mission-projection",
          "simulator");
  private final Environment env;
  private final RestClient client;

  public ServiceHttp(Environment env) {
    this.env = env;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    client = RestClient.builder().requestFactory(factory).build();
  }

  private String url(String service, String path) {
    if (!SERVICES.contains(service) || !path.startsWith("/") || path.startsWith("//"))
      throw new IllegalArgumentException("Unknown service/path");
    return env.getProperty("msc.services." + service + ".url", "http://" + service + ":8080")
        + path;
  }

  private void credentials(HttpHeaders headers) {
    if (env.getProperty("msc.security.mode", "oidc").equals("local")) {
      headers.setBasicAuth(
          "service", env.getRequiredProperty("msc.security.local.service-password"));
    } else {
      var form = new LinkedMultiValueMap<String, String>();
      form.add("grant_type", "client_credentials");
      form.add("scope", env.getProperty("msc.security.client.scope", "msc.internal"));
      var token =
          client
              .post()
              .uri(env.getRequiredProperty("msc.security.client.token-uri"))
              .headers(
                  h ->
                      h.setBasicAuth(
                          env.getRequiredProperty("msc.security.client.id"),
                          env.getRequiredProperty("msc.security.client.secret")))
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (token == null || !token.hasNonNull("access_token"))
        throw new IllegalStateException("No service access token");
      headers.setBearerAuth(token.get("access_token").asText());
    }
  }

  public <T> T get(String service, String path, Class<T> type) {
    return Objects.requireNonNull(
        client.get().uri(url(service, path)).headers(this::credentials).retrieve().body(type));
  }

  public <T> T post(
      String service, String path, Object body, String idempotencyKey, Class<T> type) {
    return Objects.requireNonNull(
        client
            .post()
            .uri(url(service, path))
            .headers(this::credentials)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(type));
  }
}
