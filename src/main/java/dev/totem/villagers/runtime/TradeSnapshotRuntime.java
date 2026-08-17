package dev.totem.villagers.runtime;

import dev.totem.villagers.mixin.MerchantMenuTraderAccessor;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;

/** Keeps an open trade view fresh without trusting client-side availability state. */
public final class TradeSnapshotRuntime {
    private static final int SNAPSHOT_INTERVAL_TICKS = 5;

    private TradeSnapshotRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TradeSnapshotRuntime::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % SNAPSHOT_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof MerchantMenu merchantMenu
                    && ((MerchantMenuTraderAccessor) merchantMenu).totemVillagers$trader() instanceof Villager villager) {
                VillagerTradeStockAuthority.syncOpenTrade(villager, player, merchantMenu);
            }
        }
    }

    /** Immediately refreshes a currently open trade view after its worker changes inventory. */
    static void refreshOpenTrade(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof MerchantMenu merchantMenu
                    && ((MerchantMenuTraderAccessor) merchantMenu).totemVillagers$trader() == villager) {
                VillagerTradeStockAuthority.syncOpenTrade(villager, player, merchantMenu);
            }
        }
    }
}
