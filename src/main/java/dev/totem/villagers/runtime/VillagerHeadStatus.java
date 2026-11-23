package dev.totem.villagers.runtime;

import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.EnumMap;
import java.util.Optional;
import java.util.WeakHashMap;

/** Work attempts publish one bounded, translated status to players tracking this entity. */
public final class VillagerHeadStatus {
    public enum Kind {
        FOOD("item/bread", true), BUYING_FOOD("item/emerald", false),
        UNREACHABLE("item/compass", true), WOOD("block/oak_log", true),
        TABLE("block/crafting_table_top", true), COMPOSTER("block/composter_side", true),
        BARREL("block/barrel_side", true), CAMPFIRE("item/campfire", true), HOE("item/wooden_hoe", true),
        ROD("item/fishing_rod", true), FIBRE("item/string", true),
        MAKING_TOOL("item/stick", false), SHEARS("item/shears", true), LOOM("block/loom_front", true), MATERIAL("item/bundle", true),
        MINE_ZONE("block/stone", true), FOREST_ZONE("block/oak_log", true), FURNACE("block/furnace_front", true),
        PICKAXE("item/wooden_pickaxe", true), AXE("item/wooden_axe", true),
        STRONG_PICKAXE("item/iron_pickaxe", true), NO_MINE_FACE("block/stone", true), NO_TREE("block/oak_log", true);

        public final String sprite;
        final boolean needsHelp;
        Kind(String sprite, boolean needsHelp) { this.sprite = sprite; this.needsHelp = needsHelp; }
        public String translation() { return "status.totem.villager." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Display(Kind kind, Optional<ItemAmount> material, Optional<String> potion, boolean pristine) {}
    private record Observation(long first, long last, int attempts, Display display) {}
    private static final WeakHashMap<Villager, EnumMap<Kind, Observation>> SEEN = new WeakHashMap<>();
    private static final AttachmentType<Display> DISPLAY = AttachmentRegistry.<Display>builder()
            .syncWith(StreamCodec.composite(ByteBufCodecs.idMapper(i -> Kind.values()[i], Kind::ordinal), Display::kind,
                    ByteBufCodecs.optional(StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ItemAmount::itemId,
                            ByteBufCodecs.VAR_INT, ItemAmount::count, ItemAmount::new)), Display::material,
                    ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(256)), Display::potion, ByteBufCodecs.BOOL, Display::pristine, Display::new),
                    AttachmentSyncPredicate.all())
            .buildAndRegister(Identifier.parse("totem-villagers:head_status_v3"));

    private VillagerHeadStatus() {}

    public static Display display(Villager worker) { return ((AttachmentTarget)worker).getAttached(DISPLAY); }

    public static Kind current(Villager worker) {
        var status = display(worker);
        return status == null ? null : status.kind();
    }

    static void report(Villager worker, Kind kind) {
        report(worker, new Display(kind, Optional.empty(), Optional.empty(), false));
    }

    static void missingMaterial(Villager worker, ItemAmount missing, net.minecraft.world.item.ItemStack exact) {
        Optional<String> variant = Optional.empty();
        boolean pristine = exact != null && !exact.is(net.minecraft.world.item.Items.LINGERING_POTION);
        if (exact != null && !pristine) {
            var contents = exact.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            // Broadcast a registry key only. Keep custom names, effects and arbitrary item components server-side.
            String id = contents == null || !contents.customEffects().isEmpty() || contents.customName().isPresent() || contents.customColor().isPresent()
                    ? "" : contents.potion().flatMap(net.minecraft.core.Holder::unwrapKey)
                        .map(key -> key.identifier().toString()).filter(key -> key.length() <= 256).orElse("");
            variant = Optional.of(id);
        }
        report(worker, new Display(Kind.MATERIAL, Optional.of(missing), variant, pristine));
    }

    private static void report(Villager worker, Display status) {
        var kind = status.kind();
        long now = worker.level().getGameTime();
        var entries = SEEN.computeIfAbsent(worker, ignored -> new EnumMap<>(Kind.class));
        if (kind == Kind.FOOD) entries.remove(Kind.BUYING_FOOD);
        if (kind == Kind.BUYING_FOOD) entries.remove(Kind.FOOD);
        var old = entries.get(kind);
        if (old == null || now - old.last > 220
                || old.display.pristine() != status.pristine() || !old.display.potion().equals(status.potion()) || !old.display.material().map(ItemAmount::itemId).equals(status.material().map(ItemAmount::itemId))) {
            entries.put(kind, new Observation(now, now, 1, status));
        } else if (old.last != now || !old.display.equals(status)) {
            entries.put(kind, new Observation(old.first, now, old.attempts + (old.last == now ? 0 : 1), status));
        }
    }

    static void clear(Villager worker, Kind... kinds) {
        var entries = SEEN.get(worker);
        if (entries != null) for (var kind : kinds) entries.remove(kind);
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 10 != 0) return;
            boolean enabled = WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() == WorkBackedTradingMode.ENFORCED;
            for (var it = SEEN.entrySet().iterator(); it.hasNext();) {
                var entry = it.next();
                var worker = entry.getKey();
                if (!(worker.level() instanceof ServerLevel level) || level.getServer() != server) continue;
                var entries = entry.getValue();
                long now = level.getGameTime();
                if (!enabled || !worker.isAlive() || worker.isRemoved() || worker.isSleeping()) entries.clear();
                entries.values().removeIf(v -> now - v.last > 220);
                Display selected = null;
                for (var item : entries.entrySet()) {
                    var obs = item.getValue();
                    if (!item.getKey().needsHelp || obs.attempts >= 3 && now - obs.first >= 60) {
                        selected = obs.display;
                        break;
                    }
                }
                if (!java.util.Objects.equals(display(worker), selected)) {
                    var data = (AttachmentTarget)worker;
                    if (selected == null) data.removeAttached(DISPLAY);
                    else data.setAttached(DISPLAY, selected);
                }
                if (entries.isEmpty()) it.remove();
            }
        });
    }
}
