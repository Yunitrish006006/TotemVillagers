package dev.totem.villagers.world;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.runtime.VillageProductionStockPolicy;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.world.ore.MinerIncidentalOreDefinitions;
import dev.totem.villagers.world.ore.MinerIncidentalOreRule;
import dev.totem.villagers.world.ore.MinerOreSafetySavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

/** Commits one Mine Zone block through Minecraft's live mining loot table. */
public final class MinerWorldWorkAction {
    private final IntSupplier deterministicRoll;

    public MinerWorldWorkAction() {
        this.deterministicRoll = null;
    }

    /** Public for deterministic GameTests; production always uses the level's random source. */
    public MinerWorldWorkAction(IntSupplier deterministicRoll) {
        this.deterministicRoll = Objects.requireNonNull(deterministicRoll, "deterministicRoll");
    }

    public boolean complete(ServerLevel level, Villager miner, BlockPos target, TagKey<Block> eligibleTargets, WorkOrder order,
                            VillagerWorkInventory inventory) {
        if (!"totem:miner".equals(order.professionId())
                || !level.isLoaded(target)
                || !WorldWorkNavigation.isWithinReach(miner, target)
                || !level.getBlockState(target).is(eligibleTargets)
                || !WorldWorkPermissions.mayWork(level, miner, target)) {
            return false;
        }
        BlockState original = level.getBlockState(target);
        Optional<MiningPlan> planned = planMining(level, miner, target, original, inventory);
        if (planned.isEmpty()) {
            return false;
        }
        MiningPlan plan = planned.get();
        boolean renewableGeneratedFace = isRenewableGeneratedMineFace(level, miner, target, original, order);
        Optional<dev.totem.villagers.inventory.WorkInventory.Reservation> reserved =
                inventory.reserveExactMatchingItem(plan.tool());
        if (reserved.isEmpty()) {
            return false;
        }
        List<ItemStack> returned = new ArrayList<>(plan.drops());
        ItemStack wornTool = wearOnce(plan.tool());
        if (!wornTool.isEmpty()) {
            returned.add(wornTool);
        }
        if (!inventory.canInsertAllExact(returned)) {
            reserved.get().rollback();
            return false;
        }
        if (!level.destroyBlock(target, false, miner, 512)) {
            reserved.get().rollback();
            return false;
        }
        if (renewableGeneratedFace) {
            level.setBlock(target, original, 3);
            if (!level.getBlockState(target).equals(original)) {
                reserved.get().rollback();
                return false;
            }
        }
        if (reserved.get().commitWithReturns(returned)) {
            MinerOreSafetySavedData.forServer(level.getServer())
                    .recordMine(miner.getUUID(), plan.incidentalIron());
            miner.swing(InteractionHand.MAIN_HAND);
            miner.playWorkSound();
            return true;
        }
        if (!renewableGeneratedFace) {
            level.setBlock(target, original, 3);
        }
        reserved.get().rollback();
        return false;
    }

    /**
     * Only the deliberately placed stone faces of a persisted world-generated village mine are deep seams. A
     * player-created Miner zone still consumes ordinary terrain normally, while an unattended generated village can
     * keep the one cobblestone input that closes its charcoal, furnace and replacement-tool cycle.
     */
    private static boolean isRenewableGeneratedMineFace(ServerLevel level, Villager miner, BlockPos target,
                                                        BlockState original, WorkOrder order) {
        if (!original.is(Blocks.STONE) || !"minecraft:cobblestone".equals(order.output().itemId())) {
            return false;
        }
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        Optional<java.util.UUID> zoneId = assignments.getAssignment(miner.getUUID())
                .filter(assignment -> "totem:miner".equals(assignment.roleId()))
                .flatMap(assignment -> assignment.workZoneId());
        if (zoneId.isEmpty()) {
            return false;
        }
        boolean targetInsideAssignedMine = assignments.getZone(zoneId.orElseThrow())
                .filter(zone -> "totem:miner".equals(zone.roleId()))
                .filter(zone -> zone.zone().contains(level.dimension().identifier().toString(),
                        new BlockCoordinate(target.getX(), target.getY(), target.getZ())))
                .isPresent();
        if (!targetInsideAssignedMine) {
            return false;
        }
        return GeneratedVillageSavedData.forServer(level.getServer()).snapshot().stream()
                .filter(village -> village.dimensionId().equals(level.dimension().identifier().toString()))
                .anyMatch(village -> village.minerZoneId().filter(zoneId.orElseThrow()::equals).isPresent());
    }

    /** Chooses the strongest personal pickaxe that produces a real loot-table result for this block. */
    private Optional<MiningPlan> planMining(ServerLevel level, Villager miner, BlockPos target, BlockState original,
                                            VillagerWorkInventory inventory) {
        Optional<MiningPlan> basePlan = inventory.snapshot().stream()
                .filter(stack -> !stack.isEmpty() && pickaxeRank(stack) >= 0 && stack.isCorrectToolForDrops(original))
                .map(stack -> stack.copyWithCount(1))
                .sorted(Comparator.comparingInt(MinerWorldWorkAction::pickaxeRank).reversed())
                .map(tool -> new MiningPlan(tool, Block.getDrops(original, level, target, null, miner, tool).stream()
                        .filter(stack -> !stack.isEmpty())
                        .map(ItemStack::copy)
                        .toList(), false))
                .filter(plan -> !plan.drops().isEmpty())
                .findFirst();
        if (basePlan.isEmpty()) {
            return Optional.empty();
        }
        MiningPlan selected = basePlan.orElseThrow();
        List<ItemStack> combinedDrops = new ArrayList<>(selected.drops());
        IncidentalOreResult incidental = incidentalOreDrops(level, miner, target, original, selected.tool());
        incidental.drops().stream()
                .map(drop -> VillageProductionStockPolicy.boundedIncidentalDrop(inventory, drop))
                .filter(drop -> !drop.isEmpty())
                .forEach(combinedDrops::add);
        return Optional.of(new MiningPlan(selected.tool(), List.copyOf(combinedDrops), incidental.iron()));
    }

    /** Resolves one live data-pack roll, then applies that ore block's normal loot table and tool requirement. */
    private IncidentalOreResult incidentalOreDrops(ServerLevel level, Villager miner, BlockPos target,
                                                   BlockState substrate, ItemStack tool) {
        Optional<MinerIncidentalOreRule> selected = MinerIncidentalOreDefinitions.catalog().select(
                level.dimension().identifier().toString(),
                target.getY(),
                tagId -> substrate.is(blockTag(tagId)),
                tagId -> level.getBiome(target).is(biomeTag(tagId)),
                deterministicRoll == null ? () -> level.getRandom().nextInt(10_000) : deterministicRoll);
        if (MinerOreSafetySavedData.forServer(level.getServer()).requiresSafetyIron(miner.getUUID())) {
            Optional<MinerIncidentalOreRule> safetyIron = eligibleIronRule(level, target, substrate);
            if (safetyIron.isPresent()) {
                selected = safetyIron;
            }
        }
        if (selected.isEmpty()) {
            return IncidentalOreResult.EMPTY;
        }
        MinerIncidentalOreRule rule = selected.orElseThrow();
        Identifier oreId = Identifier.tryParse(rule.oreBlock());
        Block ore = oreId == null ? null : BuiltInRegistries.BLOCK.getValue(oreId);
        if (ore == null || ore == Blocks.AIR) {
            return IncidentalOreResult.EMPTY;
        }
        BlockState oreState = ore.defaultBlockState();
        if (!tool.isCorrectToolForDrops(oreState)) {
            return IncidentalOreResult.EMPTY;
        }
        List<ItemStack> drops = Block.getDrops(oreState, level, target, null, miner, tool).stream()
                .filter(stack -> !stack.isEmpty())
                .map(ItemStack::copy)
                .toList();
        return new IncidentalOreResult(drops, isIronRule(rule) && !drops.isEmpty());
    }

    /** Pity applies only while a current data-pack iron profile is eligible at this exact block and height. */
    private Optional<MinerIncidentalOreRule> eligibleIronRule(ServerLevel level, BlockPos target,
                                                              BlockState substrate) {
        return MinerIncidentalOreDefinitions.catalog().snapshot().stream()
                .filter(MinerWorldWorkAction::isIronRule)
                .filter(rule -> level.dimension().identifier().toString().equals(rule.dimension()))
                .filter(rule -> substrate.is(blockTag(rule.substrateTag())))
                .filter(rule -> rule.biomeTag().isBlank()
                        || level.getBiome(target).is(biomeTag(rule.biomeTag())))
                .filter(rule -> rule.chanceAt(target.getY()) > 0)
                .findFirst();
    }

    private static boolean isIronRule(MinerIncidentalOreRule rule) {
        return "minecraft:iron_ore".equals(rule.oreBlock())
                || "minecraft:deepslate_iron_ore".equals(rule.oreBlock());
    }

    private static TagKey<Block> blockTag(String id) {
        return TagKey.create(Registries.BLOCK, Identifier.parse(id));
    }

    private static TagKey<Biome> biomeTag(String id) {
        return TagKey.create(Registries.BIOME, Identifier.parse(id));
    }

    /** Applies the one durability point that a successful autonomous mine consumes. */
    private static ItemStack wearOnce(ItemStack tool) {
        ItemStack worn = tool.copy();
        if (!worn.isDamageableItem()) {
            return worn;
        }
        if (worn.getDamageValue() + 1 >= worn.getMaxDamage()) {
            return ItemStack.EMPTY;
        }
        worn.setDamageValue(worn.getDamageValue() + 1);
        return worn;
    }

    private static int pickaxeRank(ItemStack stack) {
        if (stack.is(Items.NETHERITE_PICKAXE)) {
            return 7;
        }
        if (stack.is(Items.DIAMOND_PICKAXE)) {
            return 6;
        }
        if (stack.is(Items.IRON_PICKAXE)) {
            return 5;
        }
        if (stack.is(Items.COPPER_PICKAXE)) {
            return 4;
        }
        if (stack.is(Items.STONE_PICKAXE)) {
            return 3;
        }
        if (stack.is(Items.GOLDEN_PICKAXE)) {
            return 2;
        }
        if (stack.is(Items.WOODEN_PICKAXE)) {
            return 1;
        }
        return -1;
    }

    private record MiningPlan(ItemStack tool, List<ItemStack> drops, boolean incidentalIron) {
    }

    private record IncidentalOreResult(List<ItemStack> drops, boolean iron) {
        private static final IncidentalOreResult EMPTY = new IncidentalOreResult(List.of(), false);
    }
}
