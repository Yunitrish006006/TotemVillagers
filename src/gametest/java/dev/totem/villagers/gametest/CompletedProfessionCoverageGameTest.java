package dev.totem.villagers.gametest;

import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.trade.VillagerOfferSides;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage lock for professions declared complete in profession-rollout.md.
 * It uses Minecraft's current generated offers rather than maintaining an
 * output list that can silently drift from the game's trade data.
 */
public final class CompletedProfessionCoverageGameTest {
    private static final List<String> COMPLETE_PROFESSIONS = List.of(
            "butcher", "shepherd", "mason", "leatherworker"
    );

    @GameTest(maxTicks = 40)
    public void completedProfessionsHaveADataDrivenOrderForEveryLiveSellOffer(GameTestHelper helper) {
        List<Villager> villagers = new ArrayList<>();
        try {
            for (int index = 0; index < COMPLETE_PROFESSIONS.size(); index++) {
                String profession = COMPLETE_PROFESSIONS.get(index);
                Villager villager = spawnVillager(helper, new BlockPos(1 + index * 2, 2, 3));
                villagers.add(villager);
                setMaximumLevelProfession(villager, profession);
                MerchantOffers offers = villager.getOffers();
                require(helper, !offers.isEmpty(), "Minecraft generated no offers for " + profession);

                WorkOrderCatalog catalog = WorkOrderCatalogs.effectiveFor(
                        WorkOrderDefinitions.catalog(), "minecraft:" + profession, offers, helper.getLevel());
                List<String> uncovered = offers.stream().filter(VillagerOfferSides::isVillagerSellOffer)
                        .map(offer -> StockVariantKey.fromStack(offer.getResult(), helper.getLevel().registryAccess()))
                        .filter(result -> catalog.snapshot().values().stream()
                                .noneMatch(order -> order.professionId().equals("minecraft:" + profession)
                                        && order.outputKey().equals(result)))
                        .map(StockVariantKey::persistentString).distinct().sorted().toList();
                require(helper, uncovered.isEmpty(), "Unmapped " + profession + " sell outputs: "
                        + String.join(", ", uncovered));
            }
            helper.succeed();
        } finally {
            villagers.forEach(Villager::discard);
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setMaximumLevelProfession(Villager villager, String professionId) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", professionId));
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.setVillagerXp(VillagerData.getMinXpPerLevel(VillagerData.MAX_VILLAGER_LEVEL));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
