package dev.totem.villagers.world;

import dev.totem.villagers.work.WorkOrder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;
import java.util.List;
import dev.totem.villagers.inventory.VillagerWorkInventory;

/** Server-side shearing whose produced wool is inserted into the worker's physical inventory. */
public final class ShepherdWorldWorkAction {
    private static final double WORK_REACH_SQUARED = 16.0D;

    public boolean complete(ServerLevel level, Villager shepherd, Sheep sheep, WorkOrder order, VillagerWorkInventory inventory) {
        return shear(level, shepherd, sheep, order, inventory, null, ItemStack.EMPTY);
    }

    public boolean shear(ServerLevel level, Villager shepherd, Sheep sheep, WorkOrder order, VillagerWorkInventory inventory,
                         VillagerWorkInventory buyer, ItemStack price) {
        DyeColor outputColour = outputColour(order).orElse(null);
        if (outputColour == null || (buyer != null && !supports(order))
                || !dev.totem.villagers.runtime.VillagerWorkMemory.canTend(shepherd, sheep) || !sheep.isAlive()
                || sheep.getColor() != outputColour
                || !sheep.readyForShearing()
                || shepherd.distanceToSqr(sheep) > WORK_REACH_SQUARED || !shepherd.hasLineOfSight(sheep)
                || !dev.totem.villagers.worker.ProductionParcel.get(level.getServer()).mayTendSheep(level, shepherd, sheep.blockPosition())) {
            return false;
        }
        if (!order.matchesOutput(new ItemStack(Blocks.WOOL.pick(outputColour).asItem(), order.output().count()), level.registryAccess())) {
            return false;
        }
        var parcels = dev.totem.villagers.worker.ProductionParcel.get(level.getServer());
        var previous = parcels.claim(shepherd.getUUID());
        var site = shepherd.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
        // A paid apprentice holds the POI ticket before vanilla job memory is established.
        if (site.isEmpty() && buyer != null) site = previous.map(dev.totem.villagers.worker.ProductionParcel.Claim::station);
        if (site.isEmpty() || !site.get().dimension().equals(level.dimension())
                || !parcels.canWorkPasture(level, shepherd, site.get().pos(), sheep.blockPosition())) return false;
        var wool = new ItemStack(Blocks.WOOL.pick(outputColour).asItem(), order.output().count());
        var shears = ShearUse.best(inventory.snapshot()).orElse(null);
        if (shears == null) return false;
        var held = inventory.reserveExactMatchingItem(shears).orElse(null);
        if (held == null) return false;
        var worn = ShearUse.wearOnce(shears);
        var money = buyer == null ? null : buyer.reserveExactMatchingItem(price).orElse(null);
        var proceeds = buyer == null ? wool : price;
        var returns = worn.isEmpty() ? List.of(proceeds) : List.of(proceeds, worn);
        if ((buyer != null && (money == null || !buyer.canInsertExact(wool))) || !inventory.canInsertAllExact(returns)
                || previous.isEmpty() && !parcels.reservePasture(level, shepherd, site.get().pos(), sheep.blockPosition().below())) {
            held.rollback();
            if (money != null) money.rollback();
            return false;
        }
        if (!held.commitWithReturns(returns)) throw new IllegalStateException("Shearing inventory capacity changed");
        if (money != null && !money.commitWithReturn(wool)) throw new IllegalStateException("Wool delivery capacity changed");
        // setSheared performs the real flock-state transition without creating a second physical wool drop.
        sheep.setSheared(true);
        dev.totem.villagers.runtime.VillagerWorkMemory.claimAfterShearing(shepherd, sheep);
        shepherd.playWorkSound();
        return true;
    }

    public static boolean supports(WorkOrder order) {
        return "minecraft:sheep".equals(order.worldTargetEntityType()) && order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORLD)
                && order.requiredInputs().isEmpty() && order.output().count() == 1 && outputColour(order).isPresent();
    }

    /** Resolves only canonical vanilla coloured-wool outputs; arbitrary items cannot shear a flock. */
    public static Optional<DyeColor> outputColour(WorkOrder order) {
        if (!"minecraft:shepherd".equals(order.professionId())) {
            return Optional.empty();
        }
        return DyeColor.VALUES.stream()
                .filter(colour -> ("minecraft:" + colour.getName() + "_wool").equals(order.output().itemId()))
                .findFirst();
    }
}
