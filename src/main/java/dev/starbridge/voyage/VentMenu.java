package dev.starbridge.voyage;

import dev.starbridge.content.ContentModule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Vanilla menu synchronization keeps the reserve, room seal and refill action on the same vent. */
public final class VentMenu extends AbstractContainerMenu {
    private static final int RESERVE = 0, SEALED = 1, CANISTERS = 2;
    private final CabinPressure.Vent vent;
    private final ContainerData data;

    public VentMenu(int id, Inventory inventory) { this(id, inventory, null); }

    VentMenu(int id, Inventory inventory, CabinPressure.Vent vent) {
        super(VoyageModule.VENT_MENU.get(), id);
        this.vent = vent;
        this.data = vent == null ? new SimpleContainerData(3) : new ContainerData() {
            public int get(int index) {
                return switch (index) {
                    case RESERVE -> vent.charge();
                    case SEALED -> vent.encloses((ServerPlayer) inventory.player) ? 1 : 0;
                    case CANISTERS -> inventory.countItem(ContentModule.OXYGEN_CANISTER.get());
                    default -> 0;
                };
            }
            public void set(int index, int value) { }
            public int getCount() { return 3; }
        };
        addDataSlots(data);
    }

    public int charge() { return data.get(RESERVE); }
    public boolean sealed() { return data.get(SEALED) != 0; }
    public int canisters() { return data.get(CANISTERS); }
    public boolean canRefill() { return canisters() > 0 && charge() <= CabinPressure.CAPACITY - CabinPressure.PER_CARTRIDGE; }

    @Override public boolean stillValid(Player player) {
        if (vent == null) return true; // Client menu has only synchronized readings.
        if (!(player instanceof ServerPlayer p) || !p.isAlive() || vent.isRemoved()) return false;
        Vec3 center = Vec3.atCenterOf(vent.getBlockPos());
        return vent.eyeInCabin(p).filter(eye -> eye.distanceToSqr(center) <= 64).isPresent();
    }

    @Override public boolean clickMenuButton(Player player, int action) {
        if (action != 0 || vent == null || !stillValid(player) || player.isSpectator() || !canRefill()) return false;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (vent.refill(player, inventory.getItem(slot))) {
                inventory.setChanged();
                broadcastChanges();
                return true;
            }
        }
        return false;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
