package dev.totem.villagers.workshop;

import dev.totem.villagers.work.FarmerSuspiciousStewOrders;
import dev.totem.villagers.work.StockVariantKey;
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

/**
 * Composter-bound special stew action. It regenerates the selected vanilla
 * flower recipe and checks both the order component key and the current live
 * offer, so a plain bowl of stew can never satisfy a special-effect offer.
 */
public final class FarmerSuspiciousStewWorkshopAction implements ValidatedWorkshopAction {
    private static final double WORK_REACH_SQUARED = 16.0D;

    private final ServerLevel level;
    private final Villager villager;
    private final BlockPos jobSite;
    private final WorkOrder order;
    private final MerchantOffers offers;

    public FarmerSuspiciousStewWorkshopAction(
            ServerLevel level, Villager villager, BlockPos jobSite, WorkOrder order, MerchantOffers offers
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.villager = Objects.requireNonNull(villager, "villager");
        this.jobSite = Objects.requireNonNull(jobSite, "jobSite");
        this.order = Objects.requireNonNull(order, "order");
        this.offers = Objects.requireNonNull(offers, "offers");
    }

    public static boolean supports(WorkOrder order, ServerLevel level, BlockPos jobSite, MerchantOffers offers) {
        return FarmerSuspiciousStewOrders.isOfferBoundSuspiciousStewOrder(order)
                && level.isLoaded(jobSite)
                && level.getBlockState(jobSite).is(Blocks.COMPOSTER)
                && matchingOffer(order, offers, level) != null
                && FarmerSuspiciousStewOrders.craftingInput(order).isPresent();
    }

    @Override
    public boolean complete() {
        if (!villager.isAlive() || !isFarmer(villager)
                || villager.distanceToSqr(Vec3.atCenterOf(jobSite)) > WORK_REACH_SQUARED
                || !supports(order, level, jobSite, offers)) {
            return false;
        }
        CraftingInput input = FarmerSuspiciousStewOrders.craftingInput(order).orElse(null);
        if (input == null) {
            return false;
        }
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return false;
        }
        ItemStack produced = recipe.value().assemble(input);
        if (!order.matchesOutput(produced, level.registryAccess()) || matchingOffer(order, offers, level) == null) {
            return false;
        }
        villager.playWorkSound();
        return true;
    }

    private static MerchantOffer matchingOffer(WorkOrder order, MerchantOffers offers, ServerLevel level) {
        for (MerchantOffer offer : offers) {
            if (FarmerSuspiciousStewOrders.orderFor(offer, level).filter(order::equals).isPresent()
                    && StockVariantKey.fromStack(offer.getResult(), level.registryAccess()).equals(order.outputKey())) {
                return offer;
            }
        }
        return null;
    }

    private static boolean isFarmer(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:farmer".equals(id.toString());
    }
}
