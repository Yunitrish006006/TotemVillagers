package dev.totem.villagers.workshop;

import dev.totem.villagers.work.FletcherTippedArrowOrders;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Revalidates an offer-bound tipped-arrow recipe at the Fletcher's own table. */
public final class FletcherTippedArrowWorkshopAction implements ValidatedWorkshopAction {
    private static final double WORK_REACH_SQUARED = 16.0D;

    private final ServerLevel level;
    private final Villager villager;
    private final BlockPos jobSite;
    private final WorkOrder order;
    private final MerchantOffers offers;

    public FletcherTippedArrowWorkshopAction(
            ServerLevel level, Villager villager, BlockPos jobSite, WorkOrder order, MerchantOffers offers
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.villager = Objects.requireNonNull(villager, "villager");
        this.jobSite = Objects.requireNonNull(jobSite, "jobSite");
        this.order = Objects.requireNonNull(order, "order");
        this.offers = Objects.requireNonNull(offers, "offers");
    }

    public static boolean supports(WorkOrder order, ServerLevel level, BlockPos jobSite, MerchantOffers offers) {
        return FletcherTippedArrowOrders.isOfferBoundTippedArrowOrder(order)
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.FLETCHING_TABLE)
                && FletcherTippedArrowOrders.matchingOffer(order, offers, level) != null;
    }

    @Override
    public boolean complete() {
        if (!villager.isAlive() || !isFletcher(villager)
                || villager.distanceToSqr(Vec3.atCenterOf(jobSite)) > WORK_REACH_SQUARED
                || !supports(order, level, jobSite, offers)) {
            return false;
        }
        MerchantOffer offer = FletcherTippedArrowOrders.matchingOffer(order, offers, level);
        ItemStack potion = offer == null ? null : FletcherTippedArrowOrders.lingeringPotionFor(offer).orElse(null);
        if (potion == null) {
            return false;
        }
        CraftingInput input = FletcherTippedArrowOrders.craftingInput(potion);
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack produced = recipe.value().assemble(input);
        if (!order.matchesOutput(produced, level.registryAccess())
                || FletcherTippedArrowOrders.matchingOffer(order, offers, level) == null) {
            return false;
        }
        villager.playWorkSound();
        return true;
    }

    private static boolean isFletcher(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:fletcher".equals(id.toString());
    }
}
