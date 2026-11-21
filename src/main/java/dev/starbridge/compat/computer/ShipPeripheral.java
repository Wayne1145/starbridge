package dev.starbridge.compat.computer;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.flight.Vec3d;
import java.util.Map;

public final class ShipPeripheral implements IPeripheral {
    private final ShipCoreBlockEntity core;
    public ShipPeripheral(ShipCoreBlockEntity core) { this.core = core; }
    @Override public String getType() { return "starbridge_ship"; }
    @Override public boolean equals(IPeripheral other) { return other instanceof ShipPeripheral ship && ship.core == core; }

    @LuaFunction(mainThread = true)
    public Map<String, Object> getStatus(IComputerAccess computer) throws LuaException {
        try { core.requireComputerAccess(computer.getID(), false); return core.status(); }
        catch (RuntimeException failure) { throw failure(failure); }
    }

    @LuaFunction(mainThread = true)
    public void navigate(IComputerAccess computer, double x, double y, double z) throws LuaException {
        try { core.navigateFromComputer(computer.getID(), new Vec3d(x, y, z)); }
        catch (RuntimeException failure) { throw failure(failure); }
    }

    @LuaFunction(mainThread = true)
    public void hold(IComputerAccess computer) throws LuaException {
        try { core.holdFromComputer(computer.getID()); } catch (RuntimeException failure) { throw failure(failure); }
    }

    @LuaFunction(mainThread = true)
    public void stop(IComputerAccess computer) throws LuaException {
        try { core.stopFromComputer(computer.getID()); } catch (RuntimeException failure) { throw failure(failure); }
    }
    @LuaFunction(mainThread = true)
    public Map<String, Object> getChart(IComputerAccess computer) throws LuaException {
        try { core.requireComputerAccess(computer.getID(), false); return dev.starbridge.voyage.Voyages.chart(core); }
        catch (RuntimeException rejected) { throw failure(rejected); }
    }

    @LuaFunction(mainThread = true)
    public void setCourse(IComputerAccess computer, String destination) throws LuaException {
        try {
            core.requireComputerAccess(computer.getID(), true);
            dev.starbridge.voyage.Voyages.departRemote(core, dev.starbridge.voyage.Route.named(destination));
        } catch (RuntimeException rejected) { throw failure(rejected); }
    }

    private static LuaException failure(RuntimeException failure) {
        return new LuaException(failure.getMessage() == null ? "Flight operation failed" : failure.getMessage());
    }
}
