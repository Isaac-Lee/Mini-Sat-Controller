package msc.platform;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public final class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode value", e);
    }
  }

  public <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot decode value", e);
    }
  }

  public JsonNode tree(Object value) {
    return mapper.valueToTree(value);
  }

  public <T> T convert(Object value, Class<T> type) {
    return mapper.convertValue(value, type);
  }

  public String fingerprint(Object value) {
    try {
      // Canonicalize object fields recursively; JSON member order is not request identity.
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical(tree(value)).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private String canonical(JsonNode node) {
    if (node.isObject()) {
      var fields = new java.util.TreeMap<String, String>();
      node.fields().forEachRemaining(e -> fields.put(write(e.getKey()), canonical(e.getValue())));
      return fields.entrySet().stream()
          .map(e -> e.getKey() + ":" + e.getValue())
          .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    if (node.isArray()) {
      var elements = new java.util.ArrayList<String>();
      node.forEach(e -> elements.add(canonical(e)));
      return String.join(",", elements).transform(s -> "[" + s + "]");
    }
    return node.toString();
  }
}
