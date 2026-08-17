package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWalletSavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.trade.InventoryDrivenProfessionTrades;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerProfessionEquipment;
import dev.totem.villagers.work.VillagerWorkSavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Grants each adult worker one finite, profession-aware physical starter kit. */
public final class VillagerStarterSupplyRuntime {
    public static final int STARTING_EMERALDS = 8;
    public static final int STARTING_BREAD = 6;
    public static final int TOOLSMITH_STARTING_STRING = 12;
    public static final int TOOLSMITH_STARTING_SHEARS = 1;
    public static final int MINER_STARTING_COAL = 4;
    private VillagerStarterSupplyRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerStarterSupplyRuntime::tick);
    }

    public static void tickForGameTest(MinecraftServer server) {
        supply(server, true);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) {
            supply(server, false);
        }
    }

    private static void supply(MinecraftServer server, boolean force) {
        if (!force && WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        VillagerStarterSupplySavedData ledger = VillagerStarterSupplySavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        VillagerWalletSavedData legacyWallets = VillagerWalletSavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if (villager.isBaby()) {
                    continue;
                }
                VillagerWorkInventory inventory = inventories.inventory(villager.getUUID());
                if (!ledger.hasBase(villager.getUUID())) {
                    int legacyEmeralds = legacyWallets.balance(villager.getUUID());
                    List<ItemStack> base = List.of(
                            new ItemStack(Items.EMERALD, legacyEmeralds > 0 ? legacyEmeralds : STARTING_EMERALDS),
                            new ItemStack(Items.BREAD, STARTING_BREAD));
                    if (!inventory.insertAllExact(base)) {
                        continue;
                    }
                    if (legacyEmeralds > 0) {
                        legacyWallets.clear(villager.getUUID());
                    }
                    VillagerNutrition.grantFoundingNutrition(villager);
                    ledger.markBase(villager.getUUID());
                }
                String profession = professionId(villager);
                if ("minecraft:none".equals(profession) || "minecraft:nitwit".equals(profession)) {
                    continue;
                }
                VillagerTradeStockAuthority.ensureSpecialistTradeMenu(villager);
                migrateLegacyMerchantStock(server, villager, inventory);
                if (ledger.hasProfessionKit(villager.getUUID())) {
                    continue;
                }
                List<ItemStack> kit = new ArrayList<>();
                VillagerProfessionEquipment.tool(profession).ifPresent(tool -> kit.add(new ItemStack(tool)));
                if ("minecraft:toolsmith".equals(profession)) {
                    kit.add(new ItemStack(Items.STRING, TOOLSMITH_STARTING_STRING));
                    kit.add(new ItemStack(Items.SHEARS, TOOLSMITH_STARTING_SHEARS));
                } else if ("totem:miner".equals(profession)) {
                    // Finite ignition stock survives early metal work while the first renewable charcoal batch starts.
                    kit.add(new ItemStack(Items.COAL, MINER_STARTING_COAL));
                }
                InventoryDrivenProfessionTrades.starterStock(villager, level).ifPresent(kit::add);
                if (!kit.isEmpty() && !inventory.insertAllExact(kit)) {
                    continue;
                }
                ledger.markProfessionKit(villager.getUUID());
            }
        }
    }

    /** Moves pre-physical-release stock into slots once, including exact offer components. */
    private static void migrateLegacyMerchantStock(MinecraftServer server, Villager villager,
                                                   VillagerWorkInventory inventory) {
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        var state = states.get(villager.getUUID()).orElse(null);
        if (state == null || (state.merchantStock().isEmpty() && state.variantMerchantStock().isEmpty())) {
            return;
        }
        List<ItemStack> migrated = new ArrayList<>();
        for (var entry : state.merchantStock().entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(entry.getKey()));
            if (item == null || item == Items.AIR) {
                return;
            }
            migrated.add(new ItemStack(item, entry.getValue()));
        }
        for (var entry : state.variantMerchantStock().entrySet()) {
            ItemStack template = villager.getOffers().stream().map(offer -> offer.getResult())
                    .filter(stack -> !stack.isEmpty())
                    .filter(stack -> StockVariantKey.fromStack(stack, villager.level().registryAccess()).equals(entry.getKey()))
                    .findFirst().orElse(null);
            if (template == null) {
                return;
            }
            migrated.add(template.copyWithCount(entry.getValue()));
        }
        if (!migrated.isEmpty() && inventory.insertAllExact(migrated)) {
            states.put(state.withStock(Map.of(), Map.of(), state.diagnostic()));
        }
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }
}
