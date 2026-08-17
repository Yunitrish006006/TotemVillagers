package dev.totem.villagers.work;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkOrderDefinitionsTest {
    @Test
    void dataDocumentDecodesToValidatedOrder() {
        WorkOrder order = WorkOrder.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"totem:farmer_bread","profession":"minecraft:farmer",
                 "output":{"item":"minecraft:bread","count":1},
                 "required_inputs":[{"item":"minecraft:wheat","count":3}],
                 "sources":["workshop"],"work_ticks":40,"stock_cap":24}
                """))
                .getOrThrow(AssertionError::new);

        assertEquals("minecraft:bread", order.output().itemId());
        assertEquals(40, order.workTicks());
    }

    @Test
    void invalidDataCannotCreateAFreeWorkshopOrder() {
        assertThrows(IllegalArgumentException.class, () -> WorkOrder.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"totem:free_bread","profession":"minecraft:farmer",
                 "output":{"item":"minecraft:bread","count":1},
                 "sources":["workshop"],"work_ticks":1,"stock_cap":1}
                """))
                .getOrThrow(AssertionError::new));
    }

    @Test
    void lumberjackWorldOrderRequiresDataDrivenReplantBlock() {
        assertThrows(IllegalArgumentException.class, () -> WorkOrder.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"totem:lumberjack_oak_logs","profession":"totem:lumberjack",
                 "output":{"item":"minecraft:oak_log","count":4},
                 "sources":["world"],"world_target_tag":"totem:lumberjack_oak_logs","work_ticks":40,"stock_cap":64}
                """))
                .getOrThrow(AssertionError::new));

        WorkOrder configured = WorkOrder.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"totem:lumberjack_oak_logs","profession":"totem:lumberjack",
                 "output":{"item":"minecraft:oak_log","count":4},
                 "sources":["world"],"world_target_tag":"totem:lumberjack_oak_logs",
                 "world_replant_block":"minecraft:oak_sapling","work_ticks":40,"stock_cap":64}
                """))
                .getOrThrow(AssertionError::new);

        assertEquals("minecraft:oak_sapling", configured.worldReplantBlockId());
    }

    @Test
    void entityWorldOrderCannotAlsoDeclareABlockTarget() {
        assertThrows(IllegalArgumentException.class, () -> WorkOrder.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"totem:shepherd_white_wool","profession":"minecraft:shepherd",
                 "output":{"item":"minecraft:white_wool","count":1},"sources":["world"],
                 "world_target_tag":"totem:bad","world_target_entity_type":"minecraft:sheep","work_ticks":20,"stock_cap":16}
                """))
                .getOrThrow(AssertionError::new));
    }
}
