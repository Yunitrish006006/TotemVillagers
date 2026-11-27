package dev.totem.villagers.trade;

/** Server-owned origin marker persisted alongside the vanilla offer fields. */
public interface ProductionMaterialRow {
    boolean totemVillagers$isMaterial();
    void totemVillagers$markMaterial();
}
