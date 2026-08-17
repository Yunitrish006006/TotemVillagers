package dev.totem.villagers.worker;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.function.Predicate;

/**
 * Specialist professions are registered once but deliberately cannot be acquired
 * from arbitrary vanilla POIs. An operator assigns them only after configuring the
 * required Work Zone / managed village boundary.
 */
public final class TotemVillagerProfessions {
    public static final Identifier MINER_ID = Identifier.fromNamespaceAndPath("totem", "miner");
    public static final Identifier LUMBERJACK_ID = Identifier.fromNamespaceAndPath("totem", "lumberjack");
    public static final Identifier BUILDER_ID = Identifier.fromNamespaceAndPath("totem", "builder");
    public static final Identifier GUARD_ID = Identifier.fromNamespaceAndPath("totem", "guard");

    private static final Predicate<net.minecraft.core.Holder<PoiType>> NO_AUTOMATIC_POI_ASSIGNMENT = ignored -> false;
    private static boolean registered;

    private TotemVillagerProfessions() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        register(MINER_ID, "entity.totem.villager.miner", SoundEvents.VILLAGER_WORK_MASON);
        register(LUMBERJACK_ID, "entity.totem.villager.lumberjack", SoundEvents.VILLAGER_WORK_FLETCHER);
        register(BUILDER_ID, "entity.totem.villager.builder", SoundEvents.VILLAGER_WORK_MASON);
        register(GUARD_ID, "entity.totem.villager.guard", SoundEvents.VILLAGER_WORK_ARMORER);
        registered = true;
    }

    private static void register(Identifier id, String translationKey, net.minecraft.sounds.SoundEvent workSound) {
        if (BuiltInRegistries.VILLAGER_PROFESSION.containsKey(id)) {
            return;
        }
        VillagerProfession profession = new VillagerProfession(
                Component.translatable(translationKey),
                NO_AUTOMATIC_POI_ASSIGNMENT,
                NO_AUTOMATIC_POI_ASSIGNMENT,
                ImmutableSet.<Item>of(),
                ImmutableSet.<Block>of(),
                workSound,
                new Int2ObjectOpenHashMap<>()
        );
        Registry.register(BuiltInRegistries.VILLAGER_PROFESSION, id, profession);
    }
}
