package msc.domain.flightdynamics;

import static msc.domain.shared.Checks.text;

/** Public GP mean elements, not measured spacecraft state. Angular units are degrees. */
public record MeanElements(
    int noradId,
    String name,
    String internationalDesignator,
    String epochUtc,
    double meanMotionRevolutionsPerDay,
    double eccentricity,
    double inclinationDegrees,
    double ascendingNodeDegrees,
    double pericenterDegrees,
    double meanAnomalyDegrees,
    double bstar,
    double meanMotionDot,
    double meanMotionDdot,
    int elementSetNumber,
    int revolutionNumber,
    String classification) {
  public MeanElements {
    if (noradId < 1 || noradId > 999999999)
      throw new IllegalArgumentException("NORAD ID must be 1..999999999");
    text(name);
    text(epochUtc);
    text(classification);
    if (internationalDesignator == null) internationalDesignator = "";
    if (!internationalDesignator.isEmpty()
        && !internationalDesignator.matches("[0-9]{4}-[0-9]{3}[A-Z]{1,3}"))
      throw new IllegalArgumentException("Invalid international designator");
    for (double v :
        new double[] {
          meanMotionRevolutionsPerDay,
          eccentricity,
          inclinationDegrees,
          ascendingNodeDegrees,
          pericenterDegrees,
          meanAnomalyDegrees,
          bstar,
          meanMotionDot,
          meanMotionDdot
        }) if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite GP element");
    if (meanMotionRevolutionsPerDay <= 0
        || meanMotionRevolutionsPerDay > 20
        || eccentricity < 0
        || eccentricity >= 1
        || inclinationDegrees < 0
        || inclinationDegrees > 180
        || ascendingNodeDegrees < 0
        || ascendingNodeDegrees >= 360
        || pericenterDegrees < 0
        || pericenterDegrees >= 360
        || meanAnomalyDegrees < 0
        || meanAnomalyDegrees >= 360
        || elementSetNumber < 0
        || revolutionNumber < 0
        || !classification.matches("[UCS]"))
      throw new IllegalArgumentException("Unsupported GP elements");
  }
}
