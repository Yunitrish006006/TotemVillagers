package dev.totem.villagers.woodcutter;

import dev.totem.villagers.content.TotemVillagerBlocks;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Server-authoritative one-input menu for the Woodcutter.
 *
 * <p>The active recipes are discovered from the live server crafting registry.
 * The client receives only the selected output, its material cost, and the
 * number of choices; choosing a button remains a validated menu action on the
 * server.</p>
 */
public final class WoodcutterMenu extends AbstractContainerMenu {
    public static final int INPUT_SLOT = 0;
    public static final int RESULT_SLOT = 1;
    private static final int INVENTORY_START = 2;
    private static final int INVENTORY_END = 38;

    private final ContainerLevelAccess access;
    private final Level level;
    private final Container input;
    private final ResultContainer result = new ResultContainer();
    private final DataSlot selectedRecipe = DataSlot.standalone();
    private final DataSlot recipeCount = DataSlot.standalone();
    private final DataSlot requiredInputCount = DataSlot.standalone();
    private List<WoodcutterRecipes.Match> recipes = List.of();
    private ItemStack listedInput = ItemStack.EMPTY;
    private long lastSoundTime;

    public WoodcutterMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, ContainerLevelAccess.NULL);
    }

    public WoodcutterMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(TotemVillagerMenus.WOODCUTTER, containerId);
        this.access = access;
        this.level = playerInventory.player.level();
        this.input = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                WoodcutterMenu.this.slotsChanged(this);
            }
        };
        selectedRecipe.set(-1);
        addDataSlot(selectedRecipe);
        addDataSlot(recipeCount);
        addDataSlot(requiredInputCount);

        addSlot(new Slot(input, 0, 20, 33) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return WoodcutterRecipes.acceptsInput(stack);
            }
        });
        addSlot(new Slot(result, 0, 143, 33) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return WoodcutterMenu.this.liveSelectedRecipe()
                        .filter(match -> ItemStack.matches(getItem(), match.output()))
                        .isPresent();
            }

            @Override
            public void onTake(Player player, ItemStack stack) {
                WoodcutterMenu.this.consumeSelectedRecipe(player);
                super.onTake(player, stack);
            }
        });
        addStandardInventorySlots(playerInventory, 8, 84);
    }

    public int selectedRecipeIndex() {
        return selectedRecipe.get();
    }

    public int recipeCount() {
        return recipeCount.get();
    }

    public int requiredInputCount() {
        return requiredInputCount.get();
    }

    public boolean hasInputItem() {
        return getSlot(INPUT_SLOT).hasItem();
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, TotemVillagerBlocks.WOODCUTTER);
    }

    @Override
    public boolean clickMenuButton(Player player, int index) {
        if (index < 0 || index >= recipes.size()) {
            return false;
        }
        selectedRecipe.set(index);
        setupResult();
        broadcastChanges();
        return true;
    }

    @Override
    public void slotsChanged(Container container) {
        if (container != input) {
            return;
        }
        ItemStack current = input.getItem(0);
        if (!ItemStack.isSameItemSameComponents(listedInput, current)) {
            listedInput = current.isEmpty() ? ItemStack.EMPTY : current.copyWithCount(1);
            recipes = WoodcutterRecipes.matching(level, current);
            recipeCount.set(recipes.size());
            selectedRecipe.set(recipes.isEmpty() ? -1 : 0);
        }
        setupResult();
        broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack original = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        original = stack.copy();
        if (index == RESULT_SLOT) {
            if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else if (index == INPUT_SLOT) {
            if (!moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (WoodcutterRecipes.acceptsInput(stack)) {
            if (!moveItemStackTo(stack, INPUT_SLOT, RESULT_SLOT, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < 29) {
            if (!moveItemStackTo(stack, 29, INVENTORY_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, INVENTORY_START, 29, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, position) -> clearContainer(player, input));
    }

    private void setupResult() {
        int index = selectedRecipe.get();
        ItemStack stack = input.getItem(0);
        if (index < 0 || index >= recipes.size()) {
            result.setItem(0, ItemStack.EMPTY);
            requiredInputCount.set(0);
            return;
        }
        WoodcutterRecipes.Match match = liveSelectedRecipe().orElse(null);
        if (match == null) {
            result.setItem(0, ItemStack.EMPTY);
            requiredInputCount.set(0);
            return;
        }
        requiredInputCount.set(match.inputCount());
        if (stack.getCount() < match.inputCount()) {
            result.setItem(0, ItemStack.EMPTY);
            return;
        }
        result.setItem(0, match.output().copy());
    }

    private void consumeSelectedRecipe(Player player) {
        int index = selectedRecipe.get();
        if (index < 0 || index >= recipes.size()) {
            return;
        }
        WoodcutterRecipes.Match match = liveSelectedRecipe().orElse(null);
        if (match == null) {
            return;
        }
        ItemStack stack = input.getItem(0);
        if (stack.getCount() < match.inputCount()) {
            return;
        }
        stack.shrink(match.inputCount());
        input.setChanged();
        access.execute((level, pos) -> {
            if (lastSoundTime != level.getGameTime()) {
                level.playSound(null, pos, SoundEvents.UI_STONECUTTER_TAKE_RESULT, net.minecraft.sounds.SoundSource.BLOCKS,
                        1.0F, 1.0F);
                lastSoundTime = level.getGameTime();
            }
        });
    }

    private java.util.Optional<WoodcutterRecipes.Match> liveSelectedRecipe() {
        int index = selectedRecipe.get();
        if (index < 0 || index >= recipes.size()) {
            return java.util.Optional.empty();
        }
        Identifier id = recipes.get(index).id();
        return WoodcutterRecipes.matching(level, input.getItem(0)).stream()
                .filter(match -> match.id().equals(id))
                .findFirst();
    }
}
