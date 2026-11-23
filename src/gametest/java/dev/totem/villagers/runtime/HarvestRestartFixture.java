package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.world.FarmerWorldWorkAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Runs beside the starter household in a distant chunk that stays absent during the middle boot. */
public final class HarvestRestartFixture {
    private static final BlockPos SOIL = new BlockPos(3205, 100, 3205);
    private static final Path RECORD = Path.of("harvest-test-farmer");
    private UUID id;
    private long savedTime;
    private boolean verified;
    private long loadedAt = -1;

    public void watchLoad() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager farmer && farmer.getUUID().equals(id)) {
                requireRecord(farmer, 422);
                loadedAt = level.getGameTime();
            }
        });
    }

    public void prepare(ServerLevel level) {
        level.getChunkAt(SOIL);
        var station = GlobalPos.of(level.dimension(), SOIL.west().above());
        level.setBlockAndUpdate(station.pos(), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(SOIL, Blocks.FARMLAND.defaultBlockState());
        var farmer = (Villager) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager")).create(level, EntitySpawnReason.COMMAND);
        farmer.setNoAi(true);
        farmer.snapTo(SOIL.getX() + .5, SOIL.getY() + 1, SOIL.getZ() + .5);
        farmer.setVillagerData(farmer.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:farmer")))));
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, station);
        var bed = SOIL.east(2).above();
        level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
        farmer.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
        if (!level.addFreshEntity(farmer)) throw new IllegalStateException("Cannot spawn harvest fixture");
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(farmer.getUUID());
        stock.insertExact(new ItemStack(Items.WOODEN_HOE));
        var crop = (CropBlock) Blocks.CARROTS;
        var order = WorkOrderDefinitions.catalog().snapshot().get("totem:farmer_carrot");
        for (int i = 0; i < 2; i++) {
            level.setBlockAndUpdate(SOIL.above(), crop.getStateForAge(crop.getMaxAge()));
            if (!ProductionParcel.get(level.getServer()).acquire(level, farmer, station, SOIL, true)
                    || !new FarmerWorldWorkAction().complete(level, farmer, SOIL.above(), order, stock))
                throw new IllegalStateException("Could not deliver harvest");
            VillageFoodForecast.observe(level, farmer, i == 0 ? 30000 : 422);
        }
        requireRecord(farmer, 422);
        try {
            Files.writeString(RECORD, farmer.getUUID() + "\n" + level.getGameTime());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save forecast fixture identity", e);
        }
    }

    public void restore() throws IOException {
        var data = Files.readAllLines(RECORD);
        id = UUID.fromString(data.getFirst());
        savedTime = Long.parseLong(data.get(1));
    }

    public boolean observe(ServerLevel level, boolean finalBoot, int tick) {
        if (verified) return true;
        if (!finalBoot || tick < 120) {
            if (level.isLoaded(SOIL) || level.getEntity(id) != null) throw new IllegalStateException("Farm should be unloaded");
            return tick >= 120;
        }
        level.setChunkForced(SOIL.getX() >> 4, SOIL.getZ() >> 4, true);
        if (!(level.getEntity(id) instanceof Villager farmer)) return false;
        if (level.getGameTime() - savedTime < 200) throw new IllegalStateException("Unloaded interval too short");
        if (loadedAt < 0) throw new IllegalStateException("Missing farm load event");
        long sinceLoad = level.getGameTime() - loadedAt;
        if (VillageFoodForecast.harvest(farmer).elapsed() < 522) {
            if (sinceLoad > 205) throw new IllegalStateException("Production clock did not resume");
            return false;
        }
        long lastSample = level.getGameTime();
        if (!VillagerRuntimeBudget.due(lastSample, id, 100)) lastSample--;
        if (!VillagerRuntimeBudget.due(lastSample, id, 100)) throw new IllegalStateException("Unexpected sampling tick");
        requireRecord(farmer, Math.toIntExact(422 + lastSample - loadedAt));
        verified = true;
        return true;
    }

    private static void requireRecord(Villager farmer, int elapsed) {
        var record = VillageFoodForecast.harvest(farmer);
        if (record == null || !record.produced() || record.interval() != 30000 || record.elapsed() != elapsed
                || !record.station().pos().equals(SOIL.west().above()))
            throw new IllegalStateException("Unexpected harvest clock, expected " + elapsed + " loaded ticks: " + record);
    }
}
