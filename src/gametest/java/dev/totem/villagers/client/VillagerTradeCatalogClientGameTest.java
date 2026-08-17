package dev.totem.villagers.client;

import dev.totem.villagers.trade.FarmerGatheredCropTrades;
import dev.totem.villagers.trade.GatheredMaterialTrades;
import dev.totem.villagers.trade.InventoryDrivenProfessionTrades;
import dev.totem.villagers.trade.ToolsmithBackpackMaterialTrades;
import dev.totem.villagers.trade.ToolsmithBucketTrades;
import dev.totem.villagers.trade.VillagerOfferSides;
import dev.totem.villagers.work.CartographerExplorerMapRules;
import dev.totem.villagers.work.LibrarianEnchantingEquipmentRules;
import dev.totem.villagers.work.LibrarianEnchantingRules;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Renders the complete live enforced-mode trade catalogue as vanilla-styled,
 * item-rendered review sheets. The screenshots are intentionally generated
 * from registries and production policy so documentation cannot silently drift.
 */
@SuppressWarnings("UnstableApiUsage")
public final class VillagerTradeCatalogClientGameTest implements FabricClientGameTest {
    private static final int ENTRIES_PER_PAGE = 6;
    private static final List<String> PROFESSION_IDS = List.of(
            "minecraft:farmer", "minecraft:fisherman", "minecraft:shepherd", "minecraft:fletcher",
            "minecraft:librarian", "minecraft:cartographer", "minecraft:cleric", "minecraft:armorer",
            "minecraft:weaponsmith", "minecraft:toolsmith", "minecraft:butcher", "minecraft:leatherworker",
            "minecraft:mason", TotemVillagerProfessions.MINER_ID.toString(),
            TotemVillagerProfessions.LUMBERJACK_ID.toString(), TotemVillagerProfessions.BUILDER_ID.toString(),
            TotemVillagerProfessions.GUARD_ID.toString()
    );

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            List<ProfessionCatalog> catalogs = singleplayer.getServer().computeOnServer(server ->
                    PROFESSION_IDS.stream().map(id -> catalog(server.overworld(), id)).toList());
            require(catalogs.stream().filter(catalog -> !catalog.noTradesByDesign()).allMatch(catalog -> !catalog.entries().isEmpty()),
                    "One or more trading professions produced an empty authoritative catalogue");
            for (ProfessionCatalog catalog : catalogs) {
                int pages = Math.max(1, Math.ceilDiv(catalog.entries().size(), ENTRIES_PER_PAGE));
                for (int page = 0; page < pages; page++) {
                    int from = page * ENTRIES_PER_PAGE;
                    int to = Math.min(catalog.entries().size(), from + ENTRIES_PER_PAGE);
                    List<CatalogEntry> entries = from >= to ? List.of() : catalog.entries().subList(from, to);
                    int selectedPage = page;
                    context.setScreen(() -> new TradeCatalogScreen(catalog, entries, selectedPage, pages));
                    context.waitForScreen(TradeCatalogScreen.class);
                    context.waitTicks(4);
                    context.takeScreenshot(String.format(Locale.ROOT, "trade-catalog-%s-%02d-of-%02d",
                            fileName(catalog.professionId()), page + 1, pages));
                    // Screenshot capture is completed on a later render frame. Keep this
                    // page alive long enough that the next page cannot tear the capture.
                    context.waitTicks(4);
                }
            }
            context.setScreen(() -> null);
        }
    }

    private static ProfessionCatalog catalog(ServerLevel level, String professionId) {
        Villager villager = createVillager(level, professionId);
        try {
            MerchantOffers vanilla = allVanillaOffers(level, villager);
            Map<String, Integer> vanillaSellLevels = vanillaSellLevels(level, villager);
            List<CatalogEntry> entries = new ArrayList<>();
            for (int tradeLevel = 1; tradeLevel <= 5; tradeLevel++) {
                for (MerchantOffer offer : vanillaOffersAtLevel(level, villager, tradeLevel)) {
                    if (!VillagerOfferSides.isVillagerSellOffer(offer)) {
                        entries.add(entry(offer, tradeLevel, Direction.PLAYER_SELLS, "Vanilla purchase"));
                    }
                }
            }

            if (TotemVillagerProfessions.MINER_ID.toString().equals(professionId)) {
                GatheredMaterialTrades.minerCatalogOffers().forEach(offer ->
                        entries.add(entry(offer, 0, Direction.PLAYER_BUYS, "Physical gathered stock")));
            } else if (TotemVillagerProfessions.LUMBERJACK_ID.toString().equals(professionId)) {
                GatheredMaterialTrades.lumberjackCatalogOffers().forEach(offer ->
                        entries.add(entry(offer, 0, Direction.PLAYER_BUYS, "Physical gathered stock")));
            } else if (!professionId.startsWith("totem:")) {
                for (MerchantOffer offer : InventoryDrivenProfessionTrades.catalogSellOffers(villager, vanilla, level)) {
                    int levelHint = vanillaSellLevels.getOrDefault(resultKey(level, offer), 0);
                    entries.add(entry(offer, levelHint, Direction.PLAYER_BUYS,
                            levelHint == 0 ? "Work-backed fallback" : "Vanilla price, physical stock"));
                }
                if ("minecraft:farmer".equals(professionId)) {
                    FarmerGatheredCropTrades.catalogOffers().forEach(offer ->
                            entries.add(entry(offer, 0, Direction.PLAYER_BUYS, "Gathered crop stock")));
                }
                if ("minecraft:toolsmith".equals(professionId)) {
                    entries.add(entry(ToolsmithBucketTrades.catalogOffer(), 0, Direction.PLAYER_BUYS,
                            "Crafted bucket stock"));
                    ToolsmithBackpackMaterialTrades.catalogOffers(level).forEach(offer ->
                            entries.add(entry(offer, 0, Direction.PLAYER_SELLS, "Remnant backpack material")));
                }
                if ("minecraft:librarian".equals(professionId)) {
                    addLibrarianDynamicEntries(entries);
                }
                if ("minecraft:cartographer".equals(professionId)) {
                    addExplorerMapEntries(entries);
                }
            }

            List<CatalogEntry> distinct = distinct(entries).stream()
                    .sorted(Comparator.comparing(CatalogEntry::direction)
                            .thenComparingInt(CatalogEntry::level)
                            .thenComparing(CatalogEntry::sortKey))
                    .toList();
            distinct.forEach(VillagerTradeCatalogClientGameTest::validateEntry);
            boolean noTradesByDesign = TotemVillagerProfessions.BUILDER_ID.toString().equals(professionId)
                    || TotemVillagerProfessions.GUARD_ID.toString().equals(professionId);
            return new ProfessionCatalog(professionId, distinct, noTradesByDesign);
        } finally {
            villager.discard();
        }
    }

    private static void addLibrarianDynamicEntries(List<CatalogEntry> entries) {
        for (int level = 1; level <= 5; level++) {
            ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 8),
                    Optional.of(new ItemCost(Items.BOOK, 1)), book, 1, 10, 0.0F);
            String policy = "Lv" + level + " book: power " + LibrarianEnchantingRules.enchantingPower(level)
                    + ", lapis " + LibrarianEnchantingRules.lapisCost(level)
                    + ", price 8-64";
            entries.add(entry(offer, level, Direction.PLAYER_BUYS, policy, "Enchanted Book"));
        }
        for (var definition : LibrarianEnchantingEquipmentRules.definitions()) {
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, definition.baseEmeraldPrice()),
                    definition.baseStack(), 1, 10, 0.0F);
            entries.add(entry(offer, definition.minimumLibrarianLevel(), Direction.PLAYER_BUYS,
                    "Enchanting-table result; base " + definition.baseEmeraldPrice() + " + enchant value",
                    "Enchanted " + definition.baseStack().getHoverName().getString()));
        }
    }

    private static void addExplorerMapEntries(List<CatalogEntry> entries) {
        for (var definition : CartographerExplorerMapRules.definitionsForLevel(5)) {
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, definition.emeraldPrice()),
                    Optional.of(new ItemCost(Items.COMPASS, 1)), new ItemStack(Items.FILLED_MAP),
                    1, definition.villagerXp(), 0.0F);
            entries.add(entry(offer, definition.minimumVillagerLevel(), Direction.PLAYER_BUYS,
                    "Produced explorer map", title(definition.id()) + " Map"));
        }
    }

    private static MerchantOffers allVanillaOffers(ServerLevel level, Villager villager) {
        MerchantOffers offers = new MerchantOffers();
        for (int tradeLevel = 1; tradeLevel <= 5; tradeLevel++) {
            offers.addAll(vanillaOffersAtLevel(level, villager, tradeLevel));
        }
        return offers;
    }

    private static MerchantOffers vanillaOffersAtLevel(ServerLevel level, Villager villager, int tradeLevel) {
        MerchantOffers result = new MerchantOffers();
        VillagerProfession profession = villager.getVillagerData().profession().value();
        ResourceKey<TradeSet> key = profession.getTrades(tradeLevel);
        if (key == null) {
            return result;
        }
        TradeSet set = level.registryAccess().lookupOrThrow(Registries.TRADE_SET).getOptional(key).orElse(null);
        if (set == null) {
            return result;
        }
        LootContext context = tradeContext(level, villager, set);
        for (var trade : set.getTrades()) {
            MerchantOffer offer = trade.value().getOffer(context);
            if (offer != null) {
                result.add(offer);
            }
        }
        return result;
    }

    private static LootContext tradeContext(ServerLevel level, Villager villager, TradeSet set) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, villager.position())
                .withParameter(LootContextParams.THIS_ENTITY, villager)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);
        return new LootContext.Builder(params).create(set.randomSequence());
    }

    private static Map<String, Integer> vanillaSellLevels(ServerLevel level, Villager villager) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int tradeLevel = 1; tradeLevel <= 5; tradeLevel++) {
            for (MerchantOffer offer : vanillaOffersAtLevel(level, villager, tradeLevel)) {
                if (VillagerOfferSides.isVillagerSellOffer(offer)) {
                    result.putIfAbsent(resultKey(level, offer), tradeLevel);
                }
            }
        }
        return result;
    }

    private static String resultKey(ServerLevel level, MerchantOffer offer) {
        return StockVariantKey.fromStack(offer.getResult(), level.registryAccess()).persistentString();
    }

    @SuppressWarnings("unchecked")
    private static Villager createVillager(ServerLevel level, String professionId) {
        EntityType<?> raw = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("villager"));
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (raw == null || profession == null) {
            throw new AssertionError("Missing catalogue entity or profession " + professionId);
        }
        Villager villager = ((EntityType<Villager>) raw).create(level, EntitySpawnReason.COMMAND);
        if (villager == null) {
            throw new AssertionError("Could not create catalogue villager for " + professionId);
        }
        villager.setPos(0.5D, 64.0D, 0.5D);
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.setVillagerXp(VillagerData.getMinXpPerLevel(VillagerData.MAX_VILLAGER_LEVEL));
        return villager;
    }

    private static CatalogEntry entry(MerchantOffer offer, int level, Direction direction, String policy) {
        return entry(offer, level, direction, policy, "");
    }

    private static CatalogEntry entry(MerchantOffer offer, int level, Direction direction,
                                      String policy, String displayName) {
        ItemStack first = offer.getBaseCostA().copy();
        ItemStack second = offer.getCostB().copy();
        ItemStack result = offer.getResult().copy();
        String name = displayName.isBlank()
                ? (direction == Direction.PLAYER_BUYS
                    ? result.getHoverName().getString()
                    : first.getHoverName().getString())
                : displayName;
        return new CatalogEntry(first, second, result, level, direction, policy, name);
    }

    private static List<CatalogEntry> distinct(List<CatalogEntry> entries) {
        Map<String, CatalogEntry> result = new LinkedHashMap<>();
        for (CatalogEntry entry : entries) {
            result.putIfAbsent(entry.signature(), entry);
        }
        return List.copyOf(result.values());
    }

    private static void validateEntry(CatalogEntry entry) {
        require(!entry.first().isEmpty() && entry.first().getCount() > 0 && !entry.result().isEmpty()
                        && entry.result().getCount() > 0,
                "Invalid empty/count-zero trade catalogue row: " + entry);
        require(entry.first().getCount() <= entry.first().getMaxStackSize()
                        && (entry.second().isEmpty()
                            || entry.second().getCount() <= entry.second().getMaxStackSize())
                        && entry.result().getCount() <= entry.result().getMaxStackSize(),
                "Trade catalogue row exceeds a physical stack: " + entry);
    }

    private static String title(String id) {
        String[] words = id.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String fileName(String professionId) {
        return professionId.substring(professionId.indexOf(':') + 1).replace('_', '-');
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private enum Direction {
        PLAYER_SELLS,
        PLAYER_BUYS
    }

    private record ProfessionCatalog(String professionId, List<CatalogEntry> entries, boolean noTradesByDesign) {
        private ProfessionCatalog {
            entries = List.copyOf(entries);
        }
    }

    private record CatalogEntry(ItemStack first, ItemStack second, ItemStack result, int level,
                                Direction direction, String policy, String displayName) {
        private CatalogEntry {
            first = first.copy();
            second = second.copy();
            result = result.copy();
        }

        private String signature() {
            return direction + "|" + BuiltInRegistries.ITEM.getKey(first.getItem()) + "|" + first.getCount()
                    + "|" + BuiltInRegistries.ITEM.getKey(second.getItem()) + "|" + second.getCount()
                    + "|" + BuiltInRegistries.ITEM.getKey(result.getItem()) + "|" + result.getCount()
                    + "|" + displayName;
        }

        private String sortKey() {
            return BuiltInRegistries.ITEM.getKey(result.getItem()) + "|" + displayName;
        }
    }

    private static final class TradeCatalogScreen extends Screen {
        private static final int COLUMNS = 2;
        private static final int ROWS = 3;
        private static final int HEADER_HEIGHT = 38;
        private static final int FOOTER_HEIGHT = 24;
        private static final int ROW_HEIGHT = 56;
        private final ProfessionCatalog catalog;
        private final List<CatalogEntry> entries;
        private final int page;
        private final int pages;

        private TradeCatalogScreen(ProfessionCatalog catalog, List<CatalogEntry> entries, int page, int pages) {
            super(Component.literal("Villager Trade Catalogue"));
            this.catalog = catalog;
            this.entries = List.copyOf(entries);
            this.page = page;
            this.pages = pages;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta) {
            graphics.fill(0, 0, width, height, 0xFF2A241D);
            graphics.fill(6, 6, width - 6, height - 6, 0xFFC6B58E);
            graphics.fill(9, 9, width - 9, HEADER_HEIGHT, 0xFF3B3026);
            graphics.centeredText(font, Component.literal(title(catalog.professionId().substring(
                            catalog.professionId().indexOf(':') + 1)) + " trades • "
                            + (page + 1) + "/" + pages + " • " + catalog.entries().size() + " options"),
                    width / 2, 18, 0xFFFFFFFF);

            if (entries.isEmpty()) {
                graphics.centeredText(font, Component.literal("No player trade rows by design"),
                        width / 2, height / 2 - 5, 0xFF493C2D);
            }
            int columnWidth = (width - 20) / COLUMNS;
            for (int index = 0; index < Math.min(entries.size(), COLUMNS * ROWS); index++) {
                int column = index / ROWS;
                int row = index % ROWS;
                int x = 10 + column * columnWidth;
                int y = HEADER_HEIGHT + 4 + row * ROW_HEIGHT;
                drawEntry(graphics, entries.get(index), x, y, columnWidth - 4);
            }
            int footerY = height - FOOTER_HEIGHT;
            graphics.fill(9, footerY, width - 9, height - 9, 0xFF3B3026);
            graphics.centeredText(font,
                    Component.literal("Green: sell   •   Blue: buy from stock   •   Lv0: dynamic"),
                    width / 2, footerY + 7, 0xFFE8DEC8);
        }

        private void drawEntry(GuiGraphicsExtractor graphics, CatalogEntry entry, int x, int y, int width) {
            int accent = entry.direction() == Direction.PLAYER_SELLS ? 0xFF5D8B54 : 0xFF527EA8;
            graphics.fill(x, y, x + width, y + ROW_HEIGHT - 3, 0xFF6E5B43);
            graphics.fill(x + 2, y + 2, x + width - 2, y + ROW_HEIGHT - 5, 0xFFE3D5B4);
            graphics.fill(x + 2, y + 2, x + 5, y + ROW_HEIGHT - 5, accent);

            drawStack(graphics, entry.first(), x + 9, y + 6);
            if (!entry.second().isEmpty()) {
                graphics.text(font, "+", x + 27, y + 11, 0xFF4B3D2D, false);
                drawStack(graphics, entry.second(), x + 37, y + 6);
            }
            graphics.text(font, "→", x + 58, y + 11, 0xFF4B3D2D, false);
            drawStack(graphics, entry.result(), x + 71, y + 6);

            String level = entry.level() > 0 ? "Lv" + entry.level() : "Lv0";
            List<String> nameLines = wrappedLines(level + " " + entry.displayName(), width - 102, 3);
            for (int line = 0; line < nameLines.size(); line++) {
                graphics.text(font, nameLines.get(line), x + 94, y + 5 + line * 12, 0xFF2D251D, false);
            }
            if (nameLines.size() == 1) {
                graphics.text(font, clipped(entry.policy(), width - 102), x + 94, y + 19, 0xFF655440, false);
            }
            String counts = entry.first().getCount()
                    + (entry.second().isEmpty() ? "" : "+" + entry.second().getCount())
                    + " → " + entry.result().getCount();
            graphics.text(font, counts, x + 9, y + 40, 0xFF655440, false);
        }

        private void drawStack(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
            graphics.item(stack, x, y);
            graphics.itemDecorations(font, stack, x, y);
        }

        private String clipped(String text, int availableWidth) {
            if (font.width(text) <= availableWidth) {
                return text;
            }
            String suffix = "…";
            int length = text.length();
            while (length > 1 && font.width(text.substring(0, length) + suffix) > availableWidth) {
                length--;
            }
            return text.substring(0, length) + suffix;
        }

        private List<String> wrappedLines(String text, int availableWidth, int maximumLines) {
            List<String> lines = new ArrayList<>();
            String remaining = text.trim();
            while (!remaining.isEmpty() && lines.size() < maximumLines) {
                if (font.width(remaining) <= availableWidth) {
                    lines.add(remaining);
                    remaining = "";
                    break;
                }
                int split = remaining.length();
                while (split > 1 && font.width(remaining.substring(0, split)) > availableWidth) {
                    split--;
                }
                int wordBreak = remaining.lastIndexOf(' ', split);
                if (wordBreak > 0) {
                    split = wordBreak;
                }
                lines.add(remaining.substring(0, split).trim());
                remaining = remaining.substring(split).trim();
            }
            if (!remaining.isEmpty() && !lines.isEmpty()) {
                lines.set(lines.size() - 1, clipped(lines.getLast() + " " + remaining, availableWidth));
            }
            return List.copyOf(lines);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
