package msc.ports;

import msc.domain.spacecraftcontrol.*;

/**
 * Only an application release gate may invoke this port. Protocol framing (for example CCSDS)
 * stays behind implementations (ADR-0004); the V1 implementation is a SIMULATION JSON transport.
 */
public interface SpacecraftLinkPort {
  TransmissionRecord transmit(CommandLoad authorizedLoad);
}
