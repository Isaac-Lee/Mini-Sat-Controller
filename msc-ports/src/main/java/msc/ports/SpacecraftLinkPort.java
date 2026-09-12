package msc.ports;

import msc.domain.spacecraftcontrol.*;

/** Only an application release gate may invoke this port; no release use case exists in v0.1. */
public interface SpacecraftLinkPort {
  TransmissionRecord transmit(CommandLoad authorizedLoad);
}
