package msc.services.simulator;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;
import msc.platform.*;

/** Deterministic raw test samples, not Earth imagery or a mission L0 product. */
final class SimulationPayload {
  static final String KIND = "simulation-payload-intent";

  record Intent(
      String scenarioId,
      String commandId,
      String catalogSha256,
      long completionTick,
      double generatedMegabytes,
      String format) {}

  record Manifest(
      String intentId,
      Intent source,
      String sourceSha256,
      long byteCount,
      String sha256,
      String objectReference,
      String environment,
      String location) {}

  static void record(StateStore store, Json json, UUID scenario, SimulationCommandApi.Entry entry) {
    var source =
        new Intent(
            scenario.toString(),
            entry.command().id().value(),
            entry.catalogSha256(),
            entry.completionTick(),
            entry.catalog().resources().generatedMegabytes(),
            "MSC_SIMULATED_RAW_U8_V1");
    store.create(KIND, json.fingerprint(List.of(scenario.toString(), source.commandId())), source);
  }

  static long bytes(Intent source) {
    try {
      long count =
          BigDecimal.valueOf(source.generatedMegabytes()).movePointRight(6).longValueExact();
      if (count <= 0 || count > 64L * 1024 * 1024) throw new ArithmeticException();
      return count;
    } catch (ArithmeticException failure) {
      throw ApiException.invalid("Synthetic payload requires 1..67108864 whole bytes (decimal MB)");
    }
  }

  static InputStream samples(String sourceHash, long count) {
    byte[] pattern = HexFormat.of().parseHex(sourceHash);
    return new InputStream() {
      long position;

      @Override
      public int read() {
        return position == count
            ? -1
            : Byte.toUnsignedInt(pattern[(int) (position++ % pattern.length)]);
      }

      @Override
      public int read(byte[] target, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        if (position == count) return -1;
        int n = (int) Math.min(length, count - position);
        for (int i = 0; i < n; i++)
          target[offset + i] = pattern[(int) (position++ % pattern.length)];
        return n;
      }
    };
  }
}
