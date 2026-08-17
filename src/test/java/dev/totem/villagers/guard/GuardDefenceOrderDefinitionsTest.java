package dev.totem.villagers.guard;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardDefenceOrderDefinitionsTest {
    @Test
    void defaultOrderDecodesWithVanillaIronGolemMaterials() {
        GuardDefenceOrder order = GuardDefenceOrder.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"totem:iron_golem",
                 "required_inputs":[{"item":"minecraft:iron_block","count":4},{"item":"minecraft:carved_pumpkin","count":1}],
                 "placements":[
                   {"x":0,"y":0,"z":0,"block":"minecraft:iron_block"},
                   {"x":0,"y":1,"z":0,"block":"minecraft:iron_block"},
                   {"x":-1,"y":1,"z":0,"block":"minecraft:iron_block"},
                   {"x":1,"y":1,"z":0,"block":"minecraft:iron_block"},
                   {"x":0,"y":2,"z":0,"block":"minecraft:carved_pumpkin"}]}
                """))
                .getOrThrow(AssertionError::new);

        assertEquals("totem:iron_golem", order.id());
        assertTrue(order.matchesVanillaIronGolemMaterials());
    }
}
