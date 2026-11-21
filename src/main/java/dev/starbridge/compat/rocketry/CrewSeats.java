package dev.starbridge.compat.rocketry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * The MC1.7 original has one native driver. Each additional original seat carries one consenting
 * passenger beside that driver.
 */
final class CrewSeats {
    private static final String ROCKET = "starbridge:crew_rocket", RIDER = "starbridge:crew_rider";

    private CrewSeats() {}

    static UUID parent(Entity e) {
        return e instanceof ArmorStand && e.getPersistentData().hasUUID(ROCKET)
                ? e.getPersistentData().getUUID(ROCKET)
                : null;
    }

    static boolean riding(RocketTransfer.Job j, ServerPlayer p) {
        var e = p.getVehicle();
        if (e == null || j.rocket == null) return false;
        if (j.rocket.equals(e.getUUID())) return true;
        return e.getUUID().equals(j.seats.get(p.getUUID()))
                && j.rocket.equals(parent(e))
                && p.getUUID().equals(e.getPersistentData().getUUID(RIDER));
    }

    static Vec3 board(RocketTransfer.Job j, Entity rocket, ServerPlayer p) {
        var l = p.serverLevel();
        var positions = j.hull.seatPositions();
        var local =
                positions.stream()
                        .skip(1)
                        .filter(v -> !j.riders.containsValue(v))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("seat"));
        ArmorStand anchor = EntityType.ARMOR_STAND.create(l);
        if (anchor == null) throw new IllegalStateException("seat");
        CompoundTag state = anchor.saveWithoutId(new CompoundTag());
        state.putBoolean("Marker", true);
        state.putBoolean("Invisible", true);
        state.putBoolean("NoGravity", true);
        state.putBoolean("Invulnerable", true);
        state.putBoolean("Silent", true);
        anchor.load(state);
        anchor.noPhysics = true;
        anchor.getPersistentData().putUUID(ROCKET, j.rocket);
        anchor.getPersistentData().putUUID(RIDER, p.getUUID());
        move(j, rocket, anchor, local);
        if (!l.addFreshEntity(anchor) || !p.startRiding(anchor, true)) {
            anchor.discard();
            throw new IllegalStateException("seat");
        }
        j.seats.put(p.getUUID(), anchor.getUUID());
        return local;
    }

    static void follow(ServerLevel l, RocketTransfer.Job j, Entity rocket) {
        for (var row : new ArrayList<>(j.seats.entrySet())) {
            var anchor = l.getEntity(row.getValue());
            if (anchor == null || !j.rocket.equals(parent(anchor))) continue;
            var rider = l.getServer().getPlayerList().getPlayer(row.getKey());
            if (rider != null && !riding(j, rider)) {
                j.riders.remove(row.getKey());
                j.seats.remove(row.getKey());
                anchor.discard();
                continue;
            }
            var pos = j.riders.get(row.getKey());
            if (pos != null) move(j, rocket, anchor, pos);
        }
    }

    static void release(ServerLevel l, RocketTransfer.Job j, Map<UUID, ServerPlayer> occupants) {
        for (var row : j.seats.entrySet()) {
            var anchor = l.getEntity(row.getValue());
            if (anchor == null || !j.rocket.equals(parent(anchor))) continue;
            for (Entity occupant : anchor.getPassengers())
                if (occupant instanceof ServerPlayer p && p.getUUID().equals(row.getKey()))
                    occupants.put(row.getKey(), p);
            anchor.ejectPassengers();
            anchor.discard();
        }
    }

    private static void move(RocketTransfer.Job j, Entity rocket, Entity seat, Vec3 local) {
        var b = j.hull.box;
        double x = rocket.getX() + local.x - (b.min().getX() + b.sx() * .5),
                y = rocket.getY() + local.y - b.min().getY(),
                z = rocket.getZ() + local.z - (b.min().getZ() + b.sz() * .5);
        seat.moveTo(x, y, z, rocket.getYRot(), 0);
        seat.setDeltaMovement(Vec3.ZERO);
        seat.hurtMarked = true;
    }
}
