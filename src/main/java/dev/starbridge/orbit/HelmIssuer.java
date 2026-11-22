package dev.starbridge.orbit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.network.PilotModule;

/** The device that ordered an orbital maneuver, including a takeoff waiting for hull chunks. */
public record HelmIssuer(Station station, int computer) {
    public enum Station { HELM, COMPUTER, AGENT }
    public static final HelmIssuer HELM = new HelmIssuer(Station.HELM, -1);
    public static final HelmIssuer AGENT = new HelmIssuer(Station.AGENT, -1);
    public static HelmIssuer computer(int id) { return new HelmIssuer(Station.COMPUTER, id); }

    public HelmIssuer {
        if (station == null || (station == Station.COMPUTER ? computer < 0 : computer != -1))
            throw new IllegalArgumentException("Invalid helm station");
    }

    public void requireAuthorized(ShipCoreBlockEntity core) {
        if (station == Station.HELM) return;
        if (station == Station.COMPUTER && core.pairedComputer() != computer
                || station == Station.AGENT && !core.agentAllowed())
            throw new IllegalStateException("nav.starbridge.lift.permission");
        if (PilotModule.isBeingPiloted(core.id()) || OrbitModule.manualClaimed(core))
            throw new IllegalStateException("orbit.starbridge.human");
    }
}
