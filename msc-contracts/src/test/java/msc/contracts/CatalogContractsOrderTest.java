package msc.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import msc.contracts.CatalogContracts.ParameterRule;
import msc.contracts.CatalogContracts.ParameterType;
import org.junit.jupiter.api.Test;

/**
 * ParameterRule.allowedValues is the one Set.copyOf field outside msc-domain that feeds
 * CatalogApi's public/idempotency fingerprints (via CatalogEntry -> CommandTemplate ->
 * ParameterRule). See msc.domain.missiondefinition.ActivityDefinitionOrderTest for the underlying
 * per-JVM ordering defect this must not exhibit.
 */
class CatalogContractsOrderTest {

  private ParameterRule rule(LinkedHashSet<String> values) {
    return new ParameterRule(ParameterType.TEXT, true, 0, 1, values);
  }

  @Test
  void allowedValuesAreInNaturalStringOrderRegardlessOfInsertionOrder() {
    var forward = new LinkedHashSet<String>();
    forward.add("LOW");
    forward.add("MEDIUM");
    forward.add("HIGH");

    var reverse = new LinkedHashSet<String>();
    reverse.add("HIGH");
    reverse.add("MEDIUM");
    reverse.add("LOW");

    var expected = List.of("HIGH", "LOW", "MEDIUM");

    assertEquals(expected, List.copyOf(rule(forward).allowedValues()));
    assertEquals(expected, List.copyOf(rule(reverse).allowedValues()));
    assertEquals(rule(forward), rule(reverse));
  }

  @Test
  void emptyAllowedValuesIsAllowed() {
    assertTrue(rule(new LinkedHashSet<>()).allowedValues().isEmpty());
  }

  @Test
  void nullSetAndNullElementAreRejected() {
    assertThrows(NullPointerException.class, () -> rule(null));
    var withNull = new LinkedHashSet<String>();
    withNull.add("LOW");
    withNull.add(null);
    assertThrows(NullPointerException.class, () -> rule(withNull));
  }
}
