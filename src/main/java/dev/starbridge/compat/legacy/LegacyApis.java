package dev.starbridge.compat.legacy;

import dev.starbridge.compat.legacy.LegacyContract.Ref;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import static dev.starbridge.compat.legacy.LegacyContract.Realm.HOST;
import static dev.starbridge.compat.legacy.LegacyContract.Realm.LEGACY;
import static dev.starbridge.compat.legacy.LegacyContract.constructor;
import static dev.starbridge.compat.legacy.LegacyContract.declaredField;
import static dev.starbridge.compat.legacy.LegacyContract.field;
import static dev.starbridge.compat.legacy.LegacyContract.method;
import static dev.starbridge.compat.legacy.LegacyContract.type;

/**
 * Every class and member Starbridge uses in Retrofit 0.6.2 and the original 1.7.10 mods, in one
 * place. Touching any constant declares all of them, so {@link #verify()} sees the full contract.
 * Module ids match the install-check entries.
 */
public final class LegacyApis {
    private LegacyApis() {}

    // ---------------------------------------------------------------- Retrofit bridge (modern side)
    private static final String WORLD = "retrofit.forge.world.";
    public static final Ref LEGACY_WORLD = method("retrofit", HOST, WORLD + "LegacyContent", "legacyWorld", Level.class);
    public static final Ref PLAYER_MIRROR = method("retrofit", HOST, WORLD + "LegacyPlayers", "mirror", Player.class, "boolean");
    public static final Ref PLAYER_WRITE_BACK = method("retrofit", HOST, WORLD + "LegacyPlayers", "writeBack", Player.class, Object.class);
    public static final Ref PLAYER_SYNC_RIDING = method("retrofit", HOST, WORLD + "LegacyPlayers", "syncRiding", Player.class, Object.class);
    public static final Ref STACK_TO_MODERN = method("retrofit", HOST, WORLD + "LegacyStacks", "toModern", Object.class);

    // ---------------------------------------------------------------- Retrofit's 1.7.10 runtime
    private static final String DIMENSION_IDS = "retrofit.legacy.bridge.DimensionIds";
    public static final Ref REAL_DIMENSION = method("retrofit", LEGACY, DIMENSION_IDS, "real", "int", String.class);
    public static final Ref REAL_DIMENSION_TYPE = method("retrofit", LEGACY, DIMENSION_IDS, "realType", "int", String.class);

    private static final String FML = "cpw.mods.fml.common.";
    public static final Ref FML_INSTANCE = method("retrofit", LEGACY, FML + "Loader", "instance");
    public static final Ref FML_ACTIVE_MODS = method("retrofit", LEGACY, FML + "Loader", "getActiveModList");
    public static final Ref MOD_ID = method("retrofit", LEGACY, FML + "ModContainer", "getModId");
    public static final Ref MOD_PACKAGES = method("retrofit", LEGACY, FML + "ModContainer", "getOwnedPackages");

    private static final String DIMENSIONS = "net.minecraftforge.common.DimensionManager";
    public static final Ref DIMENSION_REGISTERED = method("retrofit", LEGACY, DIMENSIONS, "isDimensionRegistered", "int");
    public static final Ref DIMENSION_PROVIDER = method("retrofit", LEGACY, DIMENSIONS, "createProviderFor", "int");
    public static final Ref DIMENSION_REGISTER = method("retrofit", LEGACY, DIMENSIONS, "registerDimension", "int", "int");
    public static final Ref DIMENSION_UNREGISTER = method("retrofit", LEGACY, DIMENSIONS, "unregisterDimension", "int");
    public static final Ref DIMENSION_PROVIDER_TYPE = method("retrofit", LEGACY, DIMENSIONS, "getProviderType", "int");
    public static final Ref DIMENSION_PROVIDERS = declaredField("retrofit", LEGACY, DIMENSIONS, "providers");

    private static final String EVENTS = "cpw.mods.fml.common.eventhandler.";
    public static final Ref EVENT_LISTENER = type("retrofit", LEGACY, EVENTS + "IEventListener");
    public static final Ref EVENT_PRIORITY_LOWEST = field("retrofit", LEGACY, EVENTS + "EventPriority", "LOWEST");
    public static final Ref LISTENERS_REGISTER = method("retrofit", LEGACY, EVENTS + "ListenerList", "register",
            "int", EVENTS + "EventPriority", EVENTS + "IEventListener");
    public static final Ref LISTENERS_UNREGISTER = method("retrofit", LEGACY, EVENTS + "ListenerList", "unregister",
            "int", EVENTS + "IEventListener");
    public static final Ref EVENT_BUS_ID = declaredField("retrofit", LEGACY, EVENTS + "EventBus", "busID");
    public static final Ref FORGE_EVENT_BUS = field("retrofit", LEGACY, "net.minecraftforge.common.MinecraftForge", "EVENT_BUS");

    public static final String LEGACY_PLAYER = "net.minecraft.entity.player.EntityPlayer";
    public static final Ref PLAYER_MP = type("retrofit", LEGACY, "net.minecraft.entity.player.EntityPlayerMP");
    public static final Ref PLAYER_UUID = method("retrofit", LEGACY, "net.minecraft.entity.player.EntityPlayerMP", "func_110124_au");
    public static final Ref SIDE_SERVER = field("retrofit", LEGACY, "cpw.mods.fml.relauncher.Side", "SERVER");
    public static final Ref NEW_NBT = constructor("retrofit", LEGACY, "net.minecraft.nbt.NBTTagCompound");

    private static final String FLUIDS = "net.minecraftforge.fluids.";
    public static final Ref FLUID_OF_STACK = method("retrofit", LEGACY, FLUIDS + "FluidStack", "getFluid");
    public static final Ref FLUID_AMOUNT = field("retrofit", LEGACY, FLUIDS + "FluidStack", "amount");
    public static final Ref FLUID_NAME = method("retrofit", LEGACY, FLUIDS + "Fluid", "getName");
    public static final Ref FLUID_WATER = field("retrofit", LEGACY, FLUIDS + "FluidRegistry", "WATER");

    // ---------------------------------------------------------------- Galacticraft
    private static final String GALAXIES = "micdoodle8.mods.galacticraft.api.galaxies.";
    public static final Ref GC_PLANETS = method("galacticraft", LEGACY, GALAXIES + "GalaxyRegistry", "getRegisteredPlanets");
    public static final Ref GC_MOONS = method("galacticraft", LEGACY, GALAXIES + "GalaxyRegistry", "getRegisteredMoons");
    public static final Ref BODY_REACHABLE = method("galacticraft", LEGACY, GALAXIES + "CelestialBody", "getReachable");
    public static final Ref BODY_PROVIDER = method("galacticraft", LEGACY, GALAXIES + "CelestialBody", "getWorldProvider");
    public static final Ref BODY_DIMENSION = method("galacticraft", LEGACY, GALAXIES + "CelestialBody", "getDimensionID");
    public static final Ref BODY_TIER = method("galacticraft", LEGACY, GALAXIES + "CelestialBody", "getTierRequirement");
    public static final Ref BODY_NAME = method("galacticraft", LEGACY, GALAXIES + "CelestialBody", "getName");
    public static final Ref BODY_LOCALIZED_NAME = method("galacticraft", LEGACY, GALAXIES + "CelestialBody", "getLocalizedName");

    // ---------------------------------------------------------------- Advanced Rocketry
    private static final String AR = "zmaster587.advancedRocketry.";
    public static final String ROCKET_BUILDER = AR + "tile.TileRocketBuilder";
    public static final String ROCKET = AR + "entity.EntityRocket";
    public static final Ref PAD_BOUNDS = method("advancedrocketry", LEGACY, ROCKET_BUILDER, "getRocketPadBounds",
            "net.minecraft.world.World", "int", "int", "int");
    public static final Ref BUILDER_NETWORK = method("advancedrocketry", LEGACY, ROCKET_BUILDER, "useNetworkData",
            LEGACY_PLAYER, "cpw.mods.fml.relauncher.Side", "byte", "net.minecraft.nbt.NBTTagCompound");
    public static final Ref ROCKET_NETWORK = method("advancedrocketry", LEGACY, ROCKET, "useNetworkData",
            LEGACY_PLAYER, "cpw.mods.fml.relauncher.Side", "byte", "net.minecraft.nbt.NBTTagCompound");
    public static final Ref ROCKET_PACKET_TYPE = type("advancedrocketry", LEGACY, ROCKET + "$PacketType");
    public static final Ref ROCKET_STORAGE = field("advancedrocketry", LEGACY, ROCKET, "storage");

    private static final String ATMOSPHERE_TICK = AR + "api.event.AtmosphereEvent$AtmosphereTickEvent";
    public static final Ref NEW_ATMOSPHERE_TICK = constructor("advancedrocketry", LEGACY, ATMOSPHERE_TICK);
    public static final Ref TICK_ATMOSPHERE = field("advancedrocketry", LEGACY, ATMOSPHERE_TICK, "atmosphere");
    public static final Ref TICK_ENTITY = field("advancedrocketry", LEGACY, ATMOSPHERE_TICK, "entity");
    public static final Ref TICK_CANCEL = method("advancedrocketry", LEGACY, ATMOSPHERE_TICK, "setCanceled", "boolean");
    public static final Ref TICK_LISTENERS = method("advancedrocketry", LEGACY, ATMOSPHERE_TICK, "getListenerList");
    public static final Ref VACUUM = field("advancedrocketry", LEGACY, AR + "atmosphere.AtmosphereType", "VACUUM");

    private static final String AR_FLUIDS = AR + "api.AdvancedRocketryFluids";
    public static final Ref FLUID_OXYGEN = field("advancedrocketry", LEGACY, AR_FLUIDS, "fluidOxygen");
    public static final Ref FLUID_HYDROGEN = field("advancedrocketry", LEGACY, AR_FLUIDS, "fluidHydrogen");
    public static final Ref FLUID_ROCKET_FUEL = field("advancedrocketry", LEGACY, AR_FLUIDS, "fluidRocketFuel");

    private static final String MACHINES = AR + "tile.multiblock.machine.";
    public static final Ref ELECTROLYSER = type("advancedrocketry", LEGACY, MACHINES + "TileElectrolyser");
    public static final Ref CHEMICAL_REACTOR = type("advancedrocketry", LEGACY, MACHINES + "TileChemicalReactor");
    public static final Ref ROLLING_MACHINE = type("advancedrocketry", LEGACY, MACHINES + "TileRollingMachine");
    public static final Ref PRECISION_ASSEMBLER = type("advancedrocketry", LEGACY, MACHINES + "TilePrecisionAssembler");
    public static final Ref CUTTING_MACHINE = type("advancedrocketry", LEGACY, MACHINES + "TileCuttingMachine");
    public static final Ref CRYSTALLIZER = type("advancedrocketry", LEGACY, MACHINES + "TileCrystallizer");
    public static final Ref ARC_FURNACE = type("advancedrocketry", LEGACY, MACHINES + "TileElectricArcFurnace");
    public static final Ref HAND_PRESS = type("advancedrocketry", LEGACY, AR + "block.BlockPress");

    // ---------------------------------------------------------------- LibVulpes
    private static final String VULPES = "zmaster587.libVulpes.";
    public static final Ref RECIPES_INSTANCE = method("libvulpes", LEGACY, VULPES + "recipe.RecipesMachine", "getInstance");
    public static final Ref RECIPES_FOR = method("libvulpes", LEGACY, VULPES + "recipe.RecipesMachine", "getRecipes", Class.class);
    private static final String RECIPE = VULPES + "interfaces.IRecipe";
    public static final Ref RECIPE_OUTPUT = method("libvulpes", LEGACY, RECIPE, "getOutput");
    public static final Ref RECIPE_FLUID_OUTPUTS = method("libvulpes", LEGACY, RECIPE, "getFluidOutputs");
    public static final Ref RECIPE_INGREDIENTS = method("libvulpes", LEGACY, RECIPE, "getIngredients");
    public static final Ref RECIPE_FLUID_INGREDIENTS = method("libvulpes", LEGACY, RECIPE, "getFluidIngredients");
    public static final Ref RECIPE_TIME = method("libvulpes", LEGACY, RECIPE, "getTime");
    public static final Ref RECIPE_POWER = method("libvulpes", LEGACY, RECIPE, "getPower");
    public static final Ref NEW_VECTOR = constructor("libvulpes", LEGACY, VULPES + "util.Vector3F", Object.class, Object.class, Object.class);

    /** Declares the whole contract and checks it against the running game. */
    public static java.util.List<LegacyContract.Missing> verify() {
        return LegacyContract.verify();
    }
}
