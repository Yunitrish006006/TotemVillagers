package dev.totem.villagers.work;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.Optional;

/** One physical tool reserved for work/rendering instead of being mistaken for sale stock. */
public final class VillagerProfessionEquipment {
    private static final Map<String, Item> TOOLS = Map.ofEntries(
            Map.entry("minecraft:farmer", Items.IRON_HOE),
            Map.entry("minecraft:fisherman", Items.FISHING_ROD),
            Map.entry("minecraft:fletcher", Items.BOW),
            Map.entry("minecraft:shepherd", Items.SHEARS),
            Map.entry("minecraft:toolsmith", Items.IRON_PICKAXE),
            Map.entry("minecraft:weaponsmith", Items.IRON_SWORD),
            Map.entry("minecraft:armorer", Items.IRON_HELMET),
            Map.entry("minecraft:leatherworker", Items.LEATHER_BOOTS),
            Map.entry("totem:miner", Items.IRON_PICKAXE),
            Map.entry("totem:lumberjack", Items.IRON_AXE),
            Map.entry("totem:builder", Items.IRON_AXE),
            Map.entry("totem:guard", Items.IRON_SWORD)
    );

    private VillagerProfessionEquipment() {
    }

    public static Optional<Item> tool(String professionId) {
        return Optional.ofNullable(TOOLS.get(professionId));
    }
}
