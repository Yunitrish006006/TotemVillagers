package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.worker.ProductionParcel;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Collects only the native loot emitted at the moment of a qualifying livestock death. */
public final class PastureSupplies {
    private PastureSupplies() {}

    public static Consumer<ItemStack> deathDrops(ServerLevel level, LivingEntity victim, DamageSource cause,
                                                 boolean playerCredit, Consumer<ItemStack> original) {
        if (!(victim instanceof Animal animal) || animal.isAlive() || animal.isBaby() || animal.hasCustomName()
                || animal.isLeashed() || playerCredit || animal.getLastHurtByPlayerMemoryTime() > 0
                || !(animal instanceof Sheep || animal instanceof Pig || animal instanceof Chicken || animal instanceof Cow))
            return original;
        boolean natural = cause.getEntity() instanceof Wolf wolf && !wolf.isTame()
                || cause.getEntity() == null && (cause.is(DamageTypes.FALL) || cause.is(DamageTypes.DROWN)
                        || cause.is(DamageTypes.FREEZE));
        if (!natural || WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED)
            return original;
        var parcels = ProductionParcel.get(level.getServer());
        var feet = animal.blockPosition();
        var claim = parcels.pastureAt(level, feet.below()).orElse(null);
        if (claim == null || !(level.getEntity(claim.worker()) instanceof Villager keeper)
                || !keeper.isAlive() || keeper.isBaby() || !keeper.getVillagerData().profession().is(VillagerProfession.SHEPHERD)
                || keeper.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(claim.station()::equals).isEmpty()
                || !VillageWorkshopApprenticeship.available(level, keeper) || keeper.distanceToSqr(animal) > 16
                || !keeper.hasLineOfSight(animal) || !parcels.canWorkPasture(level, keeper, claim.station().pos(), feet)) return original;
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(keeper.getUUID());
        return stack -> {
            if (!(stack.is(Items.MUTTON) || stack.is(Items.CHICKEN) || stack.is(Items.PORKCHOP)
                    || stack.is(Items.BEEF) || stack.is(Items.LEATHER))) {
                original.accept(stack);
                return;
            }
            var drop = animal.spawnAtLocation(level, stack);
            if (drop != null && drop.isAlive() && stock.insertExact(drop.getItem())) {
                keeper.take(drop, drop.getItem().getCount());
                drop.discard();
            }
        };
    }
}
