package dev.totem.villagers.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeSnapshotReasonTest {
    @Test
    void mapsPersistedMessagesToLocalisableReasonCodes() {
        assertEquals("inputs_unavailable", TradeSnapshotReason.codeFor("inputs unavailable"));
        assertEquals("awaiting_stock", TradeSnapshotReason.codeFor("minecraft:bread: awaiting work stock"));
        assertEquals("blocked", TradeSnapshotReason.codeFor("an unrecognised server message"));
        assertEquals("", TradeSnapshotReason.codeFor(""));
    }
}
