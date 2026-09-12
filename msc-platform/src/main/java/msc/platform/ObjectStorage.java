package msc.platform;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** Files/streams stay in adapters; objects are immutable by content-derived keys in callers. */
@Component
@ConditionalOnProperty(name = "msc.s3.enabled", havingValue = "true")
public final class ObjectStorage implements msc.ports.ObjectStoragePort, AutoCloseable {
  private final S3Client client;
  private final String bucket;

  public ObjectStorage(Environment env) {
    bucket = env.getRequiredProperty("msc.s3.bucket");
    var builder =
        S3Client.builder()
            .region(Region.of(env.getProperty("msc.s3.region", "us-east-1")))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(java.time.Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(java.time.Duration.ofSeconds(10)));
    String endpoint = env.getProperty("msc.s3.endpoint");
    if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
    String access = env.getProperty("msc.s3.access-key");
    if (access != null && !access.isBlank())
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(access, env.getRequiredProperty("msc.s3.secret-key"))));
    client = builder.build();
  }

  public void ensureBucket() {
    try {
      client.headBucket(b -> b.bucket(bucket));
    } catch (NoSuchBucketException e) {
      client.createBucket(b -> b.bucket(bucket));
    } catch (S3Exception e) {
      if (e.statusCode() == 404) client.createBucket(b -> b.bucket(bucket));
      else throw e;
    }
  }

  private String key(String reference) {
    String prefix = "s3://" + bucket + "/";
    if (!reference.startsWith(prefix))
      throw new IllegalArgumentException("Object outside owned bucket");
    return reference.substring(prefix.length());
  }

  public InputStream read(String reference) {
    return client.getObject(b -> b.bucket(bucket).key(key(reference)));
  }

  public String write(String mediaType, InputStream content) throws IOException {
    Path file = java.nio.file.Files.createTempFile("msc-object-", ".part");
    try {
      try (var out = java.nio.file.Files.newOutputStream(file)) {
        content.transferTo(out);
      }
      return writeFile(mediaType, file);
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  public String writeFile(String mediaType, Path file) throws IOException {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var stream =
          new java.security.DigestInputStream(java.nio.file.Files.newInputStream(file), digest)) {
        stream.transferTo(OutputStream.nullOutputStream());
      }
      String key = java.util.HexFormat.of().formatHex(digest.digest());
      try {
        client.putObject(
            b -> b.bucket(bucket).key(key).contentType(mediaType).ifNoneMatch("*"),
            RequestBody.fromFile(file));
      } catch (S3Exception exists) {
        if (exists.statusCode() != 412) throw exists;
      }
      return "s3://" + bucket + "/" + key;
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public void close() {
    client.close();
  }
}
