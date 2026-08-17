package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.network.TradeSnapshotPayload;
import dev.totem.villagers.trade.TradeSnapshotSender;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.UUID;

/** Confirms the client-facing trade snapshot uses only the server's personal inventory data. */
public final class TradeSnapshotInventoryGameTest {
    @GameTest(maxTicks = 20)
    public void snapshotIncludesOnlyNonEmptyPersonalWorkSlots(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(3, 2, 3));
        try {
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(villager.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.WHEAT, 12)), "Could not seed personal work inventory");

            TradeSnapshotPayload snapshot = TradeSnapshotSender.snapshot(helper.getLevel(), villager,
                    new MerchantOffers(), 12);
            require(helper, snapshot.workInventory().equals(java.util.List.of(
                    new TradeSnapshotPayload.WorkInventorySlot(0, "minecraft:wheat", 12))),
                    "Trade snapshot did not contain the server-owned personal work slot");
            require(helper, snapshot.reservedMaterials().isEmpty(), "Unreserved villager reported protected materials");
            helper.succeed();
        } finally {
            VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer()).drain(villager.getUUID());
            villager.discard();
        }
    }

    @GameTest(maxTicks = 20)
    public void snapshotIncludesServerConfirmedWorkZoneBoundary(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(5, 2, 5));
        try {
            setSpecialistProfession(helper, villager, "miner");
            BlockPos position = villager.blockPosition();
            BlockCoordinate coordinate = new BlockCoordinate(position.getX(), position.getY(), position.getZ());
            WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(helper.getLevel().getServer());
            var zone = assignments.createZone("totem:miner", new WorkZone(UUID.randomUUID(),
                    helper.getLevel().dimension().identifier().toString(), coordinate, coordinate));
            assignments.putAssignment(new WorkerAssignment(villager.getUUID(), "totem:miner",
                    java.util.Optional.of(zone.id()), java.util.Optional.empty()));

            TradeSnapshotPayload snapshot = TradeSnapshotSender.snapshot(helper.getLevel(), villager,
                    new MerchantOffers(), 13);
            TradeSnapshotPayload.WorkZoneStatus expected = new TradeSnapshotPayload.WorkZoneStatus("totem:miner", "inside",
                    zone.id().toString(), java.util.Optional.of(new TradeSnapshotPayload.WorkZoneBoundary(
                    helper.getLevel().dimension().identifier().toString(), position.getX(), position.getY(), position.getZ(),
                    position.getX(), position.getY(), position.getZ())));
            require(helper, snapshot.workZone().equals(java.util.Optional.of(expected)),
                    "Trade snapshot did not contain the server-confirmed Work Zone status and boundary");
            helper.succeed();
        } finally {
            villager.discard();
        }
    }

    @GameTest(maxTicks = 20)
    public void snapshotShowsTheServerConfirmedRecipeInputsForALiveOffer(GameTestHelper helper) {
        Villager farmer = spawnVillager(helper, new BlockPos(3, 2, 3));
        var settings = WorkBackedTradingSettingsSavedData.forServer(helper.getLevel().getServer());
        try {
            setFarmer(helper, farmer);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 0, 1, 0.05F));
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            TradeSnapshotPayload snapshot = TradeSnapshotSender.snapshot(helper.getLevel(), farmer, offers, 14);
            require(helper, snapshot.offers().size() == 1, "Bread offer was missing from the enforced trade snapshot");
            require(helper, snapshot.offers().getFirst().recipeInputs().equals(java.util.List.of(
                            new TradeSnapshotPayload.RecipeInput("minecraft:wheat", 3))),
                    "Trade snapshot did not expose the server-confirmed bread recipe inputs");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            farmer.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setSpecialistProfession(GameTestHelper helper, Villager villager, String path) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("totem", path));
        require(helper, profession != null, "Missing totem:" + path + " profession");
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
    }

    private static void setFarmer(GameTestHelper helper, Villager villager) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer"));
        require(helper, profession != null, "Missing minecraft:farmer profession");
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
