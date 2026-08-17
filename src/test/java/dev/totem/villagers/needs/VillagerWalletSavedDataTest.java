package dev.totem.villagers.needs;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerWalletSavedDataTest {
    @Test
    void walletsOnlySpendEarnedEmeraldsAndPersistNonZeroBalances() {
        UUID buyer = UUID.fromString("00000000-0000-0000-0000-000000000901");
        UUID farmer = UUID.fromString("00000000-0000-0000-0000-000000000902");
        VillagerWalletSavedData wallets = new VillagerWalletSavedData();

        assertFalse(wallets.spend(buyer, 1));
        wallets.credit(buyer, 3);
        assertTrue(wallets.spend(buyer, 2));
        wallets.credit(farmer, 2);
        assertEquals(1, wallets.balance(buyer));
        assertEquals(2, wallets.balance(farmer));
        assertEquals(2, wallets.snapshot().size());
    }
}
