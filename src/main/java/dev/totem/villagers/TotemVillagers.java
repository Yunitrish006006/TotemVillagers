package dev.totem.villagers;

import net.fabricmc.api.ModInitializer;
import dev.totem.villagers.command.TotemVillagersCommands;
import dev.totem.villagers.guard.GuardDefenceOrderDefinitions;
import dev.totem.villagers.runtime.VillagerMaterialLogisticsRuntime;
import dev.totem.villagers.runtime.LoadedVillagerCache;
import dev.totem.villagers.runtime.VillagerResourceWorkforceRuntime;
import dev.totem.villagers.runtime.VillagerSpecialistProfessionRuntime;
import dev.totem.villagers.runtime.VillageWorldgenBootstrapRuntime;
import dev.totem.villagers.runtime.VillagerWorkshopRuntime;
import dev.totem.villagers.runtime.VillagerWorldWorkRuntime;
import dev.totem.villagers.runtime.LumberjackWoodcutterRuntime;
import dev.totem.villagers.runtime.VillagerShepherdWorkRuntime;
import dev.totem.villagers.runtime.VillagerFishermanWorkRuntime;
import dev.totem.villagers.runtime.VillagerLibrarianEnchantingRuntime;
import dev.totem.villagers.runtime.VillagerFarmerWorkRuntime;
import dev.totem.villagers.runtime.VillagerBuilderRuntime;
import dev.totem.villagers.runtime.VillagerFoodEconomyRuntime;
import dev.totem.villagers.runtime.GuardConstructionRuntime;
import dev.totem.villagers.runtime.WorldEnablementRuntime;
import dev.totem.villagers.runtime.VillagerStarterSupplyRuntime;
import dev.totem.villagers.runtime.ToolsmithVillageEconomyRuntime;
import dev.totem.villagers.runtime.TradeSnapshotRuntime;
import dev.totem.villagers.runtime.MinerCharcoalEconomyRuntime;
import dev.totem.villagers.runtime.FishermanFuelEconomyRuntime;
import dev.totem.villagers.runtime.VillagerFarmerCompostingRuntime;
import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.manual.VillagersManual;
import dev.totem.villagers.woodcutter.TotemVillagerMenus;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.worldgen.VillageUtilityFeature;
import dev.totem.villagers.worldgen.VillageUtilityPoolElement;
import dev.totem.villagers.world.ore.MinerIncidentalOreDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for server-authoritative work-backed villager trading. */
public final class TotemVillagers implements ModInitializer {
    public static final String MOD_ID = "totem-villagers";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LoadedVillagerCache.register();
        VillagersManual.register();
        TotemVillagerBlocks.register();
        VillageUtilityFeature.register();
        VillageUtilityPoolElement.register();
        TotemVillagerMenus.register();
        TotemVillagerProfessions.register();
        WorkOrderDefinitions.registerReloadListener();
        MinerIncidentalOreDefinitions.registerReloadListener();
        GuardDefenceOrderDefinitions.registerReloadListener();
        TotemVillagersCommands.register();
        VillageWorldgenBootstrapRuntime.register();
        VillagerResourceWorkforceRuntime.register();
        VillagerSpecialistProfessionRuntime.register();
        VillagerStarterSupplyRuntime.register();
        MinerCharcoalEconomyRuntime.register();
        ToolsmithVillageEconomyRuntime.register();
        FishermanFuelEconomyRuntime.register();
        VillagerFarmerCompostingRuntime.register();
        VillagerMaterialLogisticsRuntime.register();
        TradeSnapshotRuntime.register();
        VillagerWorkshopRuntime.register();
        VillagerWorldWorkRuntime.register();
        LumberjackWoodcutterRuntime.register();
        VillagerShepherdWorkRuntime.register();
        VillagerFishermanWorkRuntime.register();
        VillagerLibrarianEnchantingRuntime.register();
        VillagerFarmerWorkRuntime.register();
        VillagerBuilderRuntime.register();
        VillagerFoodEconomyRuntime.register();
        GuardConstructionRuntime.register();
        WorldEnablementRuntime.register();
        LOGGER.info("Totem Villagers initialized with physical villager inventories and finite starter supplies");
    }
}
