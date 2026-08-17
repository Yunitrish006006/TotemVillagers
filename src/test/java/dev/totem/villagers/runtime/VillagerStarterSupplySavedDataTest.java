package dev.totem.villagers.runtime;

import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerStarterSupplySavedDataTest {
    @Test
    void bredAndFundingProvenanceSurvivesSavedDataRoundTrip() {
        UUID fundedChild = UUID.fromString("00000000-0000-0000-0000-000000000701");
        UUID underfundedChild = UUID.fromString("00000000-0000-0000-0000-000000000702");
        VillagerStarterSupplySavedData original = new VillagerStarterSupplySavedData();
        original.markBred(fundedChild);
        original.markBase(fundedChild);
        original.markBred(underfundedChild);

        DataResult<Tag> encoded = VillagerStarterSupplySavedData.CODEC.encodeStart(NbtOps.INSTANCE, original);
        Tag tag = encoded.result().orElseThrow(() -> new AssertionError(encoded.error().orElseThrow().message()));
        VillagerStarterSupplySavedData decoded = VillagerStarterSupplySavedData.CODEC.parse(NbtOps.INSTANCE, tag)
                .result().orElseThrow(() -> new AssertionError("Could not decode starter-supply ledger"));

        assertTrue(decoded.isBred(fundedChild));
        assertTrue(decoded.hasBase(fundedChild));
        assertTrue(decoded.isBred(underfundedChild));
        assertFalse(decoded.hasBase(underfundedChild));
    }
}
