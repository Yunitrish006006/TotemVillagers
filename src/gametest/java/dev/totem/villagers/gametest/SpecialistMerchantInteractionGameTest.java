package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.trade.LumberjackAppleTrades;
import dev.totem.villagers.trade.MinerLapisTrades;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Verifies that custom specialist roles open an actual vanilla merchant menu. */
public final class SpecialistMerchantInteractionGameTest {
    @GameTest(maxTicks = 40)
    public void minerInteractionOpensItsMerchantScreen(GameTestHelper helper) {
        assertInteractionOpensTrade(helper, "totem:miner", MinerLapisTrades::isManagedOffer);
    }

    @GameTest(maxTicks = 40)
    public void lumberjackInteractionOpensItsMerchantScreen(GameTestHelper helper) {
        assertInteractionOpensTrade(helper, "totem:lumberjack", LumberjackAppleTrades::isManagedOffer);
    }

    private static void assertInteractionOpensTrade(GameTestHelper helper, String professionId,
                                                    java.util.function.Predicate<net.minecraft.world.item.trading.MerchantOffer> managedOffer) {
        var settings = WorkBackedTradingSettingsSavedData.forServer(helper.getLevel().getServer());
        Villager specialist = spawn(helper, new BlockPos(3, 2, 3), professionId);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            var inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(specialist.getUUID());
            if ("totem:miner".equals(professionId)) {
                require(helper, inventory.insertExact(MinerLapisTrades.result()), "Could not seed Miner merchandise");
            } else {
                require(helper, inventory.insertExact(LumberjackAppleTrades.result())
                                && inventory.insertExact(new ItemStack(Items.BREAD, 6)),
                        "Could not seed Lumberjack merchandise and survival reserve");
            }
            specialist.mobInteract(player, InteractionHand.MAIN_HAND);
            require(helper, specialist.getTradingPlayer() == player,
                    professionId + " did not start a merchant session after normal interaction");
            require(helper, specialist.getOffers().stream().anyMatch(managedOffer),
                    professionId + " merchant session did not include its server-owned material row");
            helper.succeed();
        } finally {
            specialist.setTradingPlayer(null);
            VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer()).drain(specialist.getUUID());
            specialist.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawn(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        require(helper, profession != null, "Missing profession " + professionId);
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
