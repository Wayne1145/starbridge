package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Read the static assembly plan from the same original controllers registered with the LibVulpes projector. */
public record OriginalMachineLayout(int width, int depth, int height, List<Cell> cells) {
    public enum Role { FRAME, CONTROLLER, POWER, FLUID_IN, FLUID_OUT, ITEM_IN, ITEM_OUT, CLEAR }

    public enum Station {
        ELECTROLYSER("tile.electrolyser", "TileElectrolyser", "electrolyser"),
        REACTOR("tile.chemreactor", "TileChemicalReactor", "reactor"),
        PRECISION_ASSEMBLER("precisionassemblingmachine", "TilePrecisionAssembler", "precision_assembler"),
        ROLLING_MACHINE("tile.rollingmachine", "TileRollingMachine", "rolling_machine"),
        ARC_FURNACE("tile.electricarcfurnace", "TileElectricArcFurnace", "arc_furnace");

        private final ResourceLocation id;
        private final String tile;
        private final String title;

        Station(String id, String tile, String title) {
            this.id = new ResourceLocation("advancedrocketry", id);
            this.tile = tile;
            this.title = title;
        }

        public ResourceLocation id() { return id; }
        public String title() { return title; }

        public static Optional<Station> forController(ResourceLocation id) {
            for (var station : values()) if (station.id.equals(id)) return Optional.of(station);
            return Optional.empty();
        }
    }
    public record Cell(int x, int layer, int z, Role role, ResourceLocation item, int metadata) {
        public ItemStack stack() {
            return ForgeRegistries.ITEMS.getDelegate(item).map(delegate -> {
                var stack = new ItemStack(delegate.get());
                if (metadata > 0) stack.getOrCreateTag().putInt("LegacyMeta", metadata);
                return stack;
            }).orElse(ItemStack.EMPTY);
        }
    }
    public static final ResourceLocation PROJECTOR = ResourceLocation.parse("libvulpes:item.holoprojector");

    private static final ResourceLocation AIR = ResourceLocation.parse("minecraft:air");
    private static final ResourceLocation FRAME = ResourceLocation.parse("libvulpes:blockstructureblock");
    private static final ResourceLocation BLAST_BRICK = ResourceLocation.parse("advancedrocketry:utilblock");
    private static final ResourceLocation RF_BATTERY = ResourceLocation.parse("libvulpes:rfbattery");

    public static Optional<OriginalMachineLayout> read(Station station) {
        var id = station.id();
        if (!ModList.get().isLoaded("retrofit") || !ForgeRegistries.BLOCKS.containsKey(id)) return Optional.empty();
        try {
            var realm = OriginalStarMap.gameLoader();
            if (realm == null) return Optional.empty();
            var type = realm.loadClass("zmaster587.advancedRocketry.tile.multiblock.machine." + station.tile);
            var controller = type.getConstructor().newInstance();
            Object[][][] plan = (Object[][][]) type.getMethod("getStructure").invoke(controller);
            var allowed = type.getMethod("getAllowableBlocks", Object.class);
            var host = Class.forName("retrofit.forge.world.LegacyContent").getMethod("byLegacy", Object.class);
            var legacyName = Class.forName("retrofit.forge.world.LegacyBridge").getMethod("blockName", Object.class);
            if (plan.length == 0 || plan.length > 8) return Optional.empty();
            int depth = plan[0].length, width = depth == 0 ? 0 : plan[0][0].length;
            if (width < 1 || depth < 1 || width > 8 || depth > 8) return Optional.empty();
            var cells = new ArrayList<Cell>();
            int flexiblePorts = 0;
            for (int y = 0; y < plan.length; y++) {
                if (plan[y].length != depth) return Optional.empty();
                for (int z = 0; z < depth; z++) {
                    if (plan[y][z].length != width) return Optional.empty();
                    for (int x = 0; x < width; x++) {
                        Object rule = plan[y][z][x];
                        if (rule == null) continue;
                        int layer = plan.length - 1 - y;
                        if (Character.valueOf('c').equals(rule)) {
                            cells.add(new Cell(x, layer, z, Role.CONTROLLER, id, 0));
                            continue;
                        }
                        var choices = (List<?>) allowed.invoke(controller, rule);
                        if (choices.isEmpty()) return Optional.empty();
                        Role role = role(rule);
                        Part example = part(choices.get(0), host, legacyName);
                        if (Character.valueOf('*').equals(rule)
                                && (station == Station.PRECISION_ASSEMBLER || station == Station.ARC_FURNACE)) {
                            // Flexible positions accept both structure and ports. Choose a working example.
                            char port = switch (flexiblePorts++) {
                                case 0 -> 'I';
                                case 1 -> 'O';
                                case 2 -> station == Station.PRECISION_ASSEMBLER ? 'P' : '*';
                                default -> '*';
                            };
                            Part preferred = new Part(station == Station.ARC_FURNACE ? BLAST_BRICK : FRAME, 0);
                            if (port != '*') {
                                var ports = (List<?>) allowed.invoke(controller, port);
                                if (ports.isEmpty()) return Optional.empty();
                                preferred = part(ports.get(0), host, legacyName);
                            }
                            example = null;
                            if (preferred == null) return Optional.empty();
                            for (Object choice : choices) {
                                if (preferred.equals(part(choice, host, legacyName))) {
                                    example = preferred;
                                    break;
                                }
                            }
                            role = role(port);
                        }
                        if (example == null) return Optional.empty();
                        if (example.id().equals(AIR)) role = Role.CLEAR;
                        else if (role == Role.FRAME && example.id().equals(RF_BATTERY)) role = Role.POWER;
                        cells.add(new Cell(x, layer, z, role, example.id(), example.metadata()));
                    }
                }
            }
            return Optional.of(new OriginalMachineLayout(width, depth, plan.length, List.copyOf(cells)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Starbridge.LOGGER.debug("Could not open the original assembly plan for {}", id, failure);
            return Optional.empty();
        }
    }

    private record Part(ResourceLocation id, int metadata) {}

    private static Part part(Object example, Method host, Method legacyName) throws ReflectiveOperationException {
        var legacyBlock = example.getClass().getMethod("getBlock").invoke(example);
        int meta = (int) example.getClass().getMethod("getMeta").invoke(example);
        if (host.invoke(null, legacyBlock) instanceof Block block) {
            var id = ForgeRegistries.ITEMS.getKey(block.asItem());
            if (id != null && !new ItemStack(block).isEmpty()) return new Part(id, meta >= 0 && meta <= 15 ? meta : 0);
        }
        var id = ResourceLocation.tryParse((String) legacyName.invoke(null, legacyBlock));
        if (id == null || !id.getNamespace().equals("minecraft")) return null;
        return id.equals(AIR) || ForgeRegistries.ITEMS.containsKey(id) ? new Part(id, 0) : null;
    }

    private static Role role(Object rule) {
        if (!(rule instanceof Character c)) return Role.FRAME;
        return switch (c) {
            case 'P' -> Role.POWER;
            case 'L' -> Role.FLUID_IN;
            case 'l' -> Role.FLUID_OUT;
            case 'I' -> Role.ITEM_IN;
            case 'O' -> Role.ITEM_OUT;
            default -> Role.FRAME;
        };
    }
}
