package msc.ports;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streaming content belongs here, never in domain aggregates/events. Caller closes read streams.
 */
public interface ObjectStoragePort {
  InputStream read(String objectReference) throws IOException;

  String write(String mediaType, InputStream content) throws IOException;
}
