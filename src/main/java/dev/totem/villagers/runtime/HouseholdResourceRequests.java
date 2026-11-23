package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/** Loaded observations of funded requests, tied to the buyer's current home and job. */
final class HouseholdResourceRequests {
    private static final int WAIT = 48000;
    private record Request(GlobalPos home, Optional<GlobalPos> job, String role, int ticks, long observed) {
        static final Codec<Request> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("home").forGetter(Request::home),
                GlobalPos.CODEC.optionalFieldOf("job").forGetter(Request::job),
                Codec.STRING.fieldOf("role").forGetter(Request::role),
                Codec.intRange(0, WAIT).fieldOf("ticks").forGetter(Request::ticks),
                Codec.LONG.fieldOf("observed").forGetter(Request::observed)).apply(i, Request::new));
    }
    private static final AttachmentType<Request> REQUEST = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:forest_request"), Request.CODEC);
    private static final AttachmentType<Request> QUARRY = AttachmentRegistry.createPersistent(
            Identifier.parse("totem-villagers:quarry_request"), Request.CODEC);

    static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!(entity instanceof Villager buyer)) return;
            var data = (AttachmentTarget) buyer;
            for (var type : java.util.List.of(REQUEST, QUARRY)) {
                var old = data.getAttached(type);
                if (old != null) data.setAttached(type, new Request(old.home(), old.job(), old.role(), old.ticks(), -1));
            }
        });
    }

    static void clearWood(Villager buyer) {
        ((AttachmentTarget) buyer).removeAttached(REQUEST);
    }

    static String role(Villager buyer) {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(buyer.getVillagerData().profession().value()).toString();
    }

    static void reconcile(ServerLevel level, Villager buyer) {
        reconcile(level, buyer, REQUEST);
        reconcile(level, buyer, QUARRY);
    }

    private static void reconcile(ServerLevel level, Villager buyer, AttachmentType<Request> type) {
        var old = ((AttachmentTarget) buyer).getAttached(type);
        if (old == null) return;
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(buyer.getUUID());
        if (!buyer.getBrain().getMemory(MemoryModuleType.HOME).filter(old.home()::equals).isPresent()
                || !buyer.getBrain().getMemory(MemoryModuleType.JOB_SITE).equals(old.job()) || !old.role().equals(role(buyer))
                || old.observed() >= 0 && level.getGameTime() - old.observed() > 24000
                || stock.countMatchingItem(new ItemStack(Items.EMERALD)) < (type == REQUEST ? ToolsmithVillageEconomyRuntime.WOOD_PRICE : 1)
                || type == REQUEST && stock.snapshot().stream().anyMatch(stack -> stack.is(net.minecraft.tags.ItemTags.LOGS)))
            ((AttachmentTarget)buyer).removeAttached(type);
    }

    static boolean returning(Villager worker, WorkerAssignmentSavedData jobs, ServerLevel level, String profession) {
        return role(worker).equals(profession) && jobs.getAssignment(worker.getUUID())
                .filter(a -> a.automaticallyAssigned() && a.roleId().equals(profession) && a.workZoneId().isEmpty()).isPresent()
                && dev.totem.villagers.work.VillagerWorkSavedData.forServer(level.getServer()).get(worker.getUUID())
                    .filter(s -> s.activeWork().isPresent() || !s.merchantStock().isEmpty() || !s.variantMerchantStock().isEmpty()).isEmpty();
    }

    static boolean wood(Villager buyer, GlobalPos home, long now) { return observe(buyer, home, now, REQUEST); }
    static boolean stone(Villager buyer, GlobalPos home, long now) { return observe(buyer, home, now, QUARRY); }
    static void clearStone(Villager buyer) { ((AttachmentTarget)buyer).removeAttached(QUARRY); }

    private static boolean observe(Villager buyer, GlobalPos home, long now, AttachmentType<Request> type) {
        var data = (AttachmentTarget) buyer;
        var old = data.getAttached(type);
        var job = buyer.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        String role = role(buyer);
        boolean same = old != null && old.home().equals(home) && old.job().equals(job) && old.role().equals(role)
                && (old.observed() < 0 || now - old.observed() <= 24000);
        if (same && old.observed() == now) return false;
        long gap = same && old.observed() >= 0 ? now - old.observed() : 0;
        int ticks = same ? old.ticks() : 0;
        if (gap > 0 && gap <= 100) ticks = Math.min(WAIT, ticks + (int)gap);
        data.setAttached(type, new Request(home, job, role, ticks, now));
        return ticks == WAIT && (!same || old.ticks() < WAIT || old.observed() / 100 != now / 100);
    }
    private HouseholdResourceRequests() {}
}
