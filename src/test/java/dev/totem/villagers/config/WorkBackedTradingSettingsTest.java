package dev.totem.villagers.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkBackedTradingSettingsTest {
    @Test
    void freshWorldsStartEnforcedAndCanRollBackWithoutDeletingState() {
        WorkBackedTradingSettingsSavedData data = new WorkBackedTradingSettingsSavedData();

        assertTrue(data.settings().mode().enforcesWorkBackedTrading());
        data.setMode(WorkBackedTradingMode.VANILLA_ROLLBACK);
        assertEquals(WorkBackedTradingMode.VANILLA_ROLLBACK, data.settings().mode());
    }

    @Test
    void legacyDisabledWorldMigratesOnceButCurrentDisabledChoiceIsPreserved() {
        WorkBackedTradingSettingsSavedData migrated = WorkBackedTradingSettingsSavedData
                .fromPersisted(1, WorkBackedTradingMode.DISABLED);
        WorkBackedTradingSettingsSavedData currentDisabled = WorkBackedTradingSettingsSavedData
                .fromPersisted(WorkBackedTradingSettings.CURRENT_SCHEMA_VERSION, WorkBackedTradingMode.DISABLED);

        assertEquals(WorkBackedTradingMode.ENFORCED, migrated.settings().mode());
        assertEquals(WorkBackedTradingMode.DISABLED, currentDisabled.settings().mode());
        assertFalse(currentDisabled.settings().mode().enforcesWorkBackedTrading());
    }

    @Test
    void onlyEnforcedModeInitialisesAndGatesWorkBackedTrading() {
        assertFalse(WorkBackedTradingMode.DISABLED.enforcesWorkBackedTrading());
        assertFalse(WorkBackedTradingMode.VANILLA_ROLLBACK.enforcesWorkBackedTrading());
        assertTrue(WorkBackedTradingMode.ENFORCED.enforcesWorkBackedTrading());
    }
}
