package dev.starbridge.campaign;

import dev.starbridge.Starbridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Survival expedition objectives shared by the optional Unbound routes and the standalone destinations. */
public final class CampaignModule {
    public static final ResourceKey<Level> MARS = dimension("mars");
    public static final ResourceKey<Level> BELT = dimension("asteroid_belt");
    public static final ResourceKey<Level> RELAY = dimension("deep_space");
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Starbridge.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Starbridge.ID);
    public static final RegistryObject<Item> CORE_TUBE = ITEMS.register("core_tube", SampleTube::new);
    public static final RegistryObject<Item> MOON_SAMPLE = item("lunar_sample");
    public static final RegistryObject<Item> RELIC = item("archive_fragment");
    public static final RegistryObject<Item> FLIGHT_RECORDER = item("flight_recorder");
    public static final RegistryObject<Item> ARRAY = item("relay_array");
    public static final RegistryObject<Item> SURVIVOR = item("rescue_capsule");
    public static final RegistryObject<Item> CHART = ITEMS.register("calibrated_chart", () -> new Chart(false));
    public static final RegistryObject<Item> DEEP_CHART = ITEMS.register("deep_space_chart", () -> new Chart(true));
    public static final RegistryObject<Block> TERMINAL = BLOCKS.register("relay_terminal", RelayTerminal::new);
    public static final RegistryObject<Item> TERMINAL_ITEM = ITEMS.register("relay_terminal", () -> new BlockItem(TERMINAL.get(), new Item.Properties()));
    private CampaignModule() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        bus.addListener(CampaignModule::creativeItems);
    }

    private static void creativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == Starbridge.TAB.get()) ITEMS.getEntries().forEach(entry -> event.accept(entry.get()));
    }

    private static RegistryObject<Item> item(String id) {
        return ITEMS.register(id, () -> new Item(new Item.Properties()));
    }

    private static ResourceKey<Level> dimension(String path) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(Starbridge.ID, path));
    }

    /** Navigation gates are evaluated for the pilot; crew can carry and exchange physical charts. */
    public static boolean unlocked(ServerPlayer player, String route) {
        return switch (route) {
            case "home", "moon" -> true;
            case "mars", "belt" -> CampaignData.get(player.serverLevel()).rank(player.getUUID()) >= 1;
            case "relay" -> CampaignData.get(player.serverLevel()).rank(player.getUUID()) >= 2;
            default -> false;
        };
    }

    /** Called by the flight service after a successful arrival, once per onboard player. */
    public static void arrived(ServerPlayer player, String route) {
        if (!matches(player.serverLevel(), route)) return;
        if (route.equals("mars") || route.equals("belt") || route.equals("relay")) {
            BlockPos site = CampaignSites.prepare(player.serverLevel(), route);
            if (site != null) player.sendSystemMessage(Component.translatable("campaign.starbridge.arrival." + route, site.getX(), site.getY(), site.getZ()));
            else player.sendSystemMessage(Component.translatable("campaign.starbridge.no_site"));
        } else if (route.equals("moon")) player.sendSystemMessage(Component.translatable("campaign.starbridge.arrival.moon"));
    }

    static boolean matches(ServerLevel level, String route) {
        ResourceLocation id = level.dimension().location();
        return switch (route) {
            case "home" -> level.dimension().equals(Level.OVERWORLD);
            case "moon" -> id.equals(new ResourceLocation(Starbridge.ID, "survey_moon")) || id.equals(new ResourceLocation("unbound", "moon"));
            case "mars" -> level.dimension().equals(MARS) || id.equals(new ResourceLocation("unbound", "mars"));
            case "belt" -> level.dimension().equals(BELT);
            case "relay" -> level.dimension().equals(RELAY);
            default -> false;
        };
    }

    private static final class Chart extends Item {
        private final int rank;
        Chart(boolean deep) { super(new Properties().stacksTo(1).rarity(Rarity.RARE)); rank = deep ? 2 : 1; }
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            if (player instanceof ServerPlayer serverPlayer) {
                if (!matches(serverPlayer.serverLevel(), "home")) {
                    player.displayClientMessage(Component.translatable("campaign.starbridge.home_calibration"), true);
                    return InteractionResultHolder.fail(player.getItemInHand(hand));
                }
                CampaignData.get(serverPlayer.serverLevel()).learn(player.getUUID(), rank);
                player.sendSystemMessage(Component.translatable("campaign.starbridge.calibrated." + rank));
            }
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }
    }
}
