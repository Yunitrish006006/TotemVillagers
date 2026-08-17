package dev.totem.villagers.manual;

import dev.totem.core.api.v1.manual.TotemManualSection;
import dev.totem.core.api.v1.manual.TotemModuleManualSource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Work-backed villager economy guide recorded from a composter. */
public final class VillagersManual {
    private static final TotemManualSection SECTION = new TotemManualSection(
            Identifier.fromNamespaceAndPath("totem", "villagers/manual"),
            800,
            "book.totem.villagers_manual.title",
            List.of(
                    "book.totem.villagers_manual.page.1",
                    "book.totem.villagers_manual.page.2",
                    "book.totem.villagers_manual.page.3"
            )
    );

    private VillagersManual() {
    }

    public static void register() {
        TotemModuleManualSource.register(
                SECTION,
                Identifier.fromNamespaceAndPath("deadrecall", "villagers_manual"),
                state -> state.is(Blocks.COMPOSTER)
        );
    }
}
