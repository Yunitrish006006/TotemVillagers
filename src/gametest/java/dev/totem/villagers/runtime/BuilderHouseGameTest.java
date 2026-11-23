package dev.totem.villagers.runtime;

import dev.totem.villagers.builder.*;
import dev.totem.villagers.config.*;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.worker.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

public final class BuilderHouseGameTest {
    @GameTest(environment="totem:builder_house",structure="totem:builder_house",padding=48,maxTicks=30000)
    public void buildWholeHouseWithPurchasedStone(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        var sites = BuilderSiteSavedData.forServer(server);
        var assignments = WorkerAssignmentSavedData.forServer(server);
        var anchor = h.absolutePos(new BlockPos(16, 4, 7));
        var dim = level.dimension().identifier().toString();
        var owner = UUID.randomUUID();
        BlockPos.betweenClosedStream(anchor.offset(-15,-1,-5),anchor.offset(13,-1,12))
                .forEach(p -> level.setBlock(p,Blocks.DIRT.defaultBlockState(),2));
        var id = Identifier.parse("minecraft:village/plains/houses/plains_small_house_1");
        var blueprint = VanillaVillageBlueprints.resolve(server,id,anchor).orElseThrow();
        h.assertTrue(blueprint.blocks().stream().allMatch(b -> level.getBlockState(b.position()).isAir()),"Occupied starting footprint");
        var builder = BuilderSettlementGameTest.resident(h,"totem:builder",anchor.west(),anchor.offset(-5,0,4),false);
        var supplier = BuilderSettlementGameTest.resident(h,"totem:miner",anchor.west(10),anchor.offset(-12,0,4));
        var work = assignments.createZone("totem:builder",BuilderSettlementGameTest.zone(owner,dim,anchor,anchor.offset(8,8,8)));
        var mine = assignments.createZone("totem:miner",BuilderSettlementGameTest.zone(owner,dim,anchor.west(12),anchor.west(9).above()));
        assignments.putAssignment(new WorkerAssignment(builder.getUUID(),"totem:builder",Optional.of(work.id()),Optional.empty()));
        assignments.putAssignment(new WorkerAssignment(supplier.getUUID(),"totem:miner",Optional.of(mine.id()),Optional.empty()));
        var wallet = inventories.inventory(builder.getUUID());
        var stock = inventories.inventory(supplier.getUUID());
        Map<Item,Integer> bill = new HashMap<>();
        blueprint.blocks().stream().filter(VanillaVillageBlueprints.BlueprintBlock::consumesMaterial)
                .forEach(b -> bill.merge(b.state().getBlock().asItem(),1,Integer::sum));
        h.assertTrue(bill.get(Items.OAK_DOOR) == 1 && bill.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse("minecraft:white_bed"))) == 1,
                "The door and bed should each cost one item");
        int stone = bill.get(Items.COBBLESTONE);
        bill.forEach((item,count) -> h.assertTrue((item == Items.COBBLESTONE ? stock : wallet)
                .insertExact(new ItemStack(item,count)),"Insufficient storage for bill"));
        wallet.insertExact(new ItemStack(Items.EMERALD,stone));
        wallet.insertExact(new ItemStack(Items.BREAD,32));
        stock.insertExact(new ItemStack(Items.BREAD,32));
        var site = new BuilderSite(UUID.randomUUID(),owner,builder.getUUID(),id.toString(),dim,anchor.asLong(),0).planAccess(level, blueprint);
        var order = site.inConstructionOrder(blueprint);
        sites.registerOrReplace(site,owner);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        h.runBeforeTestEnd(() -> {
            sites.removeByBuilder(builder.getUUID(),owner);
            assignments.removeZone(work.id());
            assignments.removeZone(mine.id());
            builder.discard();
            supplier.discard();
            settings.setMode(previous);
        });
        boolean[] visited = {false};
        boolean[] removed = {false};
        boolean[] reported = {false};
        int[] lastStep = {0};
        int[] receipts = {0};
        int[] purchases = {0};
        boolean[] remotePayment = {false};
        h.succeedWhen(() -> {
            if (builder.distanceToSqr(supplier) <= 16) visited[0] = true;
            int balance = stock.countMatchingItem(new ItemStack(Items.EMERALD));
            if (balance > receipts[0]) {
                purchases[0]++;
                if (builder.distanceToSqr(supplier) > 16) remotePayment[0] = true;
                receipts[0] = balance;
            }
            var progress = sites.getByBuilder(builder.getUUID()).orElse(site).nextBlockIndex();
            if (!removed[0] && progress >= 20) {
                // A resident removes one completed foundation log and returns it for the final repair.
                var first = blueprint.blocks().getFirst();
                h.assertTrue(level.getBlockState(first.position()).equals(first.state()),"Foundation log was never placed");
                h.assertTrue(level.destroyBlock(first.position(), false) && wallet.insertExact(new ItemStack(first.state().getBlock().asItem())),
                        "Could not reclaim the log");
                var active = sites.getByBuilder(builder.getUUID()).orElseThrow();
                var ops = com.mojang.serialization.JsonOps.INSTANCE;
                var saved = BuilderSite.CODEC.encodeStart(ops, active).getOrThrow();
                var restored = BuilderSite.CODEC.parse(ops, saved).getOrThrow();
                h.assertTrue(!restored.finishingCourse().isEmpty() && restored.equals(active)
                        && restored.inConstructionOrder(blueprint).equals(order),"Changed construction order on reload");
                sites.registerOrReplace(restored,owner);
                var legacy = saved.deepCopy().getAsJsonObject();
                legacy.remove("finishing_course");
                h.assertTrue(BuilderSite.CODEC.parse(ops,legacy).getOrThrow().inConstructionOrder(blueprint).equals(blueprint),
                        "Legacy site did not keep its original cursor order");
                removed[0] = true;
            }
            if (sites.getByBuilder(builder.getUUID()).isPresent()) lastStep[0] = progress;
            if (progress == order.blocks().size() && !reported[0]) {
                reported[0] = true;
                org.slf4j.LoggerFactory.getLogger("BuilderHouse").info("Finished {} steps in {} ticks. Coin receipts {}. Wallet {}. State differences {}",
                        progress,h.getTick(),stock.countMatchingItem(new ItemStack(Items.EMERALD)),
                        wallet.snapshot().stream().filter(stack -> !stack.isEmpty()).toList(),
                        blueprint.blocks().stream().filter(b -> !level.getBlockState(b.position()).equals(b.state())).toList());
            }
            h.assertTrue(progress==blueprint.blocks().size(),"Unfinished house at step "+lastStep[0]);
            for (var b : blueprint.blocks()) {
                var actual = level.getBlockState(b.position());
                // Villagers can open an installed door and occupy a finished bed.
                if (actual.is(b.state().getBlock()) && actual.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
                    actual = actual.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false);
                if (actual.is(b.state().getBlock()) && actual.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                    actual = actual.setValue(net.minecraft.world.level.block.BedBlock.OCCUPIED, false);
                h.assertTrue(actual.equals(b.state()),
                    "Mismatch at "+b.position().subtract(anchor)+" expected="+b.state()+" actual="+actual);
            }
            h.assertTrue(bill.keySet().stream().allMatch(item -> wallet.countMatchingItem(new ItemStack(item)) == 0)
                    && wallet.countMatchingItem(new ItemStack(Items.EMERALD)) == 0,"Unspent materials or payment");
            h.assertTrue(visited[0] && removed[0] && purchases[0] > 1 && !remotePayment[0] && stock.countMatchingItem(new ItemStack(Items.EMERALD))==stone
                    && stock.countMatchingItem(new ItemStack(Items.COBBLESTONE))==0,"Incomplete delivery");
        });
    }
}
