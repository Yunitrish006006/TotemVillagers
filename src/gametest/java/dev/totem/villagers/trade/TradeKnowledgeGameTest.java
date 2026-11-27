package dev.totem.villagers.trade;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;

public final class TradeKnowledgeGameTest {
    @SuppressWarnings("unchecked")
    @GameTest(environment = "totem:smith_trials")
    public void copyReadAndShareOneRealQuote(GameTestHelper h) {
        var level = h.getLevel();
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var teacher = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 3));
        var reader = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 3));
        var neighbour = h.spawnWithNoFreeWill(type, new BlockPos(5, 3, 3));
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = settings.settings().mode();
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            var prof = BuiltInRegistries.VILLAGER_PROFESSION;
            teacher.setVillagerData(teacher.getVillagerData().withProfession(prof.wrapAsHolder(prof.getValue(Identifier.parse("minecraft:cleric")))));
            var accounts = VillagerWorkInventorySavedData.forServer(level.getServer());
            var dust = new ItemStack(Items.GLOWSTONE_DUST);
            accounts.inventory(teacher.getUUID()).insertExact(dust.copyWithCount(5));
            var player = h.makeMockServerPlayerInLevel();
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOOK));
            player.setItemInHand(InteractionHand.OFF_HAND, dust.copy());
            var interaction = UseEntityCallback.EVENT.invoker();
            interaction.interact(player, level, InteractionHand.MAIN_HAND, teacher, null);
            var book = player.getMainHandItem();
            h.assertTrue(book.is(Items.WRITTEN_BOOK), "Copy did not put a manual in hand: " + book + ", shift=" + player.isShiftKeyDown() + ", offers=" + teacher.getOffers().size());
            var lesson = VillagerTradeKnowledge.read(book).orElseThrow();
            h.assertTrue(lesson.item().toString().equals("minecraft:glowstone_dust") && lesson.price() == 1 && lesson.batch() == 1
                    && player.getOffhandItem().getCount() == 1, "Book did not copy the actual offer and retain the sample");
            interaction.interact(player, level, InteractionHand.MAIN_HAND, reader, null);
            interaction.interact(player, level, InteractionHand.MAIN_HAND, reader, null);
            h.assertTrue(VillagerTradeKnowledge.known(reader).equals(List.of(lesson)) && book.getCount() == 1, "Book duplicated a lesson or was consumed");
            h.assertTrue(reader.getOffers().stream().noneMatch(o -> o.getResult().is(Items.GLOWSTONE_DUST)), "Learning minted goods");
            accounts.inventory(reader.getUUID()).insertExact(dust.copy());
            var row = reader.getOffers().stream().filter(o -> o.getResult().is(Items.GLOWSTONE_DUST)).findFirst().orElseThrow();
            h.assertTrue(!row.isOutOfStock() && row.getBaseCostA().getCount() == lesson.price(), "Student could not list physical stock");
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(reader, row);
            h.assertTrue(reader.getOffers().stream().noneMatch(o -> o.getResult().is(Items.GLOWSTONE_DUST)), "Sold-out shelf retained");
            VillagerTradeKnowledge.exchangeAtBell(level, List.of(reader, neighbour));
            h.assertTrue(VillagerTradeKnowledge.known(neighbour).equals(List.of(lesson)), "Neighbour did not learn a remembered quote");
            h.succeed();
        } finally {
            settings.setMode(previous);
            teacher.discard();
            reader.discard();
            neighbour.discard();
        }
    }
}
