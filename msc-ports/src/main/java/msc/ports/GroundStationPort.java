package msc.ports;

import msc.domain.groundoperations.GroundOperations.*;

public interface GroundStationPort {
  StationBooking book(ContactOpportunity opportunity);

  PassSession begin(StationBooking confirmedBooking);

  PassReport report(PassSession session);
}
