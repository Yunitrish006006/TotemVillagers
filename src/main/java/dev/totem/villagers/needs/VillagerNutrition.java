package dev.totem.villagers.needs;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;

/** Player-style hunger rules backed by Totem data, independent from vanilla breeding food. */
public final class VillagerNutrition {
    public static final int MAX_FOOD_LEVEL = 20;
    public static final float DEFAULT_SATURATION_LEVEL = 5.0F;
    public static final float MAX_EXHAUSTION_LEVEL = 40.0F;
    public static final int HUNGRY_AT_OR_BELOW = 8;
    public static final int EAT_UNTIL = 16;
    /** Vanilla FoodData only metabolizes when exhaustion is strictly greater than four. */
    public static final float EXHAUSTION_STEP = 4.0F;

    private VillagerNutrition() {
    }

    public static int foodLevel(Villager villager) {
        return state(villager).foodLevel();
    }

    public static float saturationLevel(Villager villager) {
        return state(villager).saturationLevel();
    }

    public static float exhaustionLevel(Villager villager) {
        return state(villager).exhaustionLevel();
    }

    public static boolean isHungry(Villager villager) {
        return foodLevel(villager) <= HUNGRY_AT_OR_BELOW;
    }

    /**
     * Retains the village economy's established passive energy budget, but feeds it through the same exhaustion and
     * saturation pipeline as a player. One pulse can consume at most one saturation or food point.
     */
    public static void digest(Villager villager) {
        addExhaustion(villager, EXHAUSTION_STEP + 0.01F);
        metabolize(villager);
    }

    /** Applies vanilla FoodData metabolism, natural regeneration and difficulty-sensitive starvation. */
    public static void tick(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        metabolize(villager);
        VillagerNutritionSavedData data = VillagerNutritionSavedData.forServer(level.getServer());
        var current = data.state(villager.getUUID());
        int timer = current.tickTimer();
        float exhaustion = current.exhaustionLevel();
        boolean hurt = villager.getHealth() < villager.getMaxHealth();
        boolean naturalRegeneration = level.getGameRules().get(GameRules.NATURAL_HEALTH_REGENERATION);

        if (naturalRegeneration && current.saturationLevel() > 0.0F && hurt
                && current.foodLevel() >= MAX_FOOD_LEVEL) {
            timer++;
            if (timer >= 10) {
                float recovered = Math.min(current.saturationLevel(), 6.0F);
                villager.heal(recovered / 6.0F);
                exhaustion = Math.min(exhaustion + recovered, MAX_EXHAUSTION_LEVEL);
                timer = 0;
            }
        } else if (naturalRegeneration && current.foodLevel() >= 18 && hurt) {
            timer++;
            if (timer >= 80) {
                villager.heal(1.0F);
                exhaustion = Math.min(exhaustion + 6.0F, MAX_EXHAUSTION_LEVEL);
                timer = 0;
            }
        } else if (current.foodLevel() <= 0) {
            timer++;
            if (timer >= 80) {
                Difficulty difficulty = level.getDifficulty();
                if (villager.getHealth() > 10.0F
                        || difficulty == Difficulty.HARD
                        || villager.getHealth() > 1.0F && difficulty == Difficulty.NORMAL) {
                    villager.hurtServer(level, villager.damageSources().starve(), 1.0F);
                }
                timer = 0;
            }
        } else {
            timer = 0;
        }

        data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(
                current.foodLevel(), current.saturationLevel(), exhaustion, timer));
    }

    /** Finite world-generation nutrition that prevents a new village from starting in a work deadlock. */
    public static void grantFoundingNutrition(Villager villager) {
        if (villager.level() instanceof ServerLevel level && foodLevel(villager) < MAX_FOOD_LEVEL) {
            VillagerNutritionSavedData.forServer(level.getServer()).reset(villager.getUUID());
        }
    }

    public static int nutrition(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0 : Math.max(0, food.nutrition());
    }

    public static float saturation(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0.0F : Math.max(0.0F, food.saturation());
    }

    /** Eats exact food variants from the protected inventory with vanilla nutrition and saturation gains. */
    public static int consumeStoredFood(Villager villager, VillagerWorkInventory inventory, ItemStack foodStack) {
        FoodProperties food = foodStack.get(DataComponents.FOOD);
        if (food == null || food.nutrition() < 1) {
            return 0;
        }
        int consumed = 0;
        while (foodLevel(villager) < EAT_UNTIL && consumed < foodStack.getCount()) {
            ItemStack one = foodStack.copyWithCount(1);
            if (inventory.takeExactMatchingItem(one).isEmpty()) {
                break;
            }
            eat(villager, food);
            consumed++;
        }
        return consumed;
    }

    public static void addExhaustion(Villager villager, float amount) {
        if (!(villager.level() instanceof ServerLevel level) || amount <= 0.0F) {
            return;
        }
        VillagerNutritionSavedData data = VillagerNutritionSavedData.forServer(level.getServer());
        var current = data.state(villager.getUUID());
        data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(
                current.foodLevel(), current.saturationLevel(),
                Math.min(current.exhaustionLevel() + amount, MAX_EXHAUSTION_LEVEL), current.tickTimer()));
    }

    /** Public for commands and deterministic GameTests. Mirrors FoodData#setFoodLevel and keeps other values intact. */
    public static void setFoodLevel(Villager villager, int next) {
        if (villager.level() instanceof ServerLevel level) {
            VillagerNutritionSavedData.forServer(level.getServer()).setFoodLevel(villager.getUUID(), next);
        }
    }

    private static VillagerNutritionSavedData.NutritionState state(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return VillagerNutritionSavedData.NutritionState.initial();
        }
        return VillagerNutritionSavedData.forServer(level.getServer()).state(villager.getUUID());
    }

    private static void eat(Villager villager, FoodProperties food) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerNutritionSavedData data = VillagerNutritionSavedData.forServer(level.getServer());
        var current = data.state(villager.getUUID());
        int nextFood = Math.max(0, Math.min(MAX_FOOD_LEVEL, current.foodLevel() + food.nutrition()));
        float nextSaturation = Math.max(0.0F,
                Math.min(nextFood, current.saturationLevel() + food.saturation()));
        data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(
                nextFood, nextSaturation, current.exhaustionLevel(), current.tickTimer()));
    }

    private static void metabolize(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        VillagerNutritionSavedData data = VillagerNutritionSavedData.forServer(level.getServer());
        var current = data.state(villager.getUUID());
        if (current.exhaustionLevel() <= EXHAUSTION_STEP) {
            return;
        }
        float exhaustion = current.exhaustionLevel() - EXHAUSTION_STEP;
        float saturation = current.saturationLevel();
        int food = current.foodLevel();
        if (saturation > 0.0F) {
            saturation = Math.max(saturation - 1.0F, 0.0F);
        } else if (level.getDifficulty() != Difficulty.PEACEFUL) {
            food = Math.max(food - 1, 0);
        }
        data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(
                food, saturation, exhaustion, current.tickTimer()));
    }
}
