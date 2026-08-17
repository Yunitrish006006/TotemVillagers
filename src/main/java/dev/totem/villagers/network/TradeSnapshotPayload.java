package dev.totem.villagers.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Compact server-to-client description of the work authority for an open
 * villager trading menu. The client never calculates stock or availability.
 */
public record TradeSnapshotPayload(
        int containerId,
        UUID villagerId,
        List<Offer> offers,
        List<WorkInventorySlot> workInventory,
        List<ReservedMaterial> reservedMaterials,
        Optional<WorkZoneStatus> workZone,
        Optional<GuardPostStatus> guardPost
) implements CustomPacketPayload {
    public static final Type<TradeSnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "trade_snapshot"));
    private static final int MAX_OFFERS = 64;
    private static final int MAX_ITEM_ID_LENGTH = 128;
    private static final int MAX_SOURCE_LENGTH = 16;
    private static final int MAX_REASON_LENGTH = 64;
    private static final int MAX_RECIPE_INPUTS = 9;
    private static final int MAX_WORK_INVENTORY_SLOTS = 27;
    private static final int MAX_RESERVED_MATERIALS = 16;
    private static final int MAX_STACK_COUNT = 99;
    private static final int MAX_RESERVED_COUNT = 4_096;
    private static final int MAX_ROLE_ID_LENGTH = 128;
    private static final int MAX_ZONE_ID_LENGTH = 36;
    private static final int MAX_DIMENSION_ID_LENGTH = 128;
    private static final int MAX_GUARD_COUNT = 4_096;

    public record Offer(
            int index,
            String itemId,
            int availableStock,
            int requiredStock,
            String source,
            int progressTicks,
            int totalWorkTicks,
            String blockedReason,
            List<RecipeInput> recipeInputs
    ) {
        public Offer {
            if (index < 0 || availableStock < 0 || requiredStock < 0 || progressTicks < 0 || totalWorkTicks < 0) {
                throw new IllegalArgumentException("Trade snapshot values cannot be negative");
            }
            itemId = requireBounded(itemId, MAX_ITEM_ID_LENGTH, "itemId");
            source = optionalBounded(source, MAX_SOURCE_LENGTH, "source");
            blockedReason = optionalBounded(blockedReason, MAX_REASON_LENGTH, "blockedReason");
            recipeInputs = List.copyOf(recipeInputs == null ? List.of() : recipeInputs);
            if (recipeInputs.size() > MAX_RECIPE_INPUTS) {
                throw new IllegalArgumentException("Too many recipe inputs in a trade snapshot offer");
            }
        }

        /** Compatibility constructor for snapshots produced before recipe inputs were displayed. */
        public Offer(int index, String itemId, int availableStock, int requiredStock, String source,
                     int progressTicks, int totalWorkTicks, String blockedReason) {
            this(index, itemId, availableStock, requiredStock, source, progressTicks, totalWorkTicks, blockedReason, List.of());
        }
    }

    /** Server-confirmed material requirement for one completion of the selected work order. */
    public record RecipeInput(String itemId, int count) {
        public RecipeInput {
            itemId = requireBounded(itemId, MAX_ITEM_ID_LENGTH, "itemId");
            if (count < 1 || count > MAX_RESERVED_COUNT) {
                throw new IllegalArgumentException("Invalid recipe-input count");
            }
        }
    }

    /** One non-empty slot in the villager's server-owned 27-slot work inventory. */
    public record WorkInventorySlot(int index, String itemId, int count) {
        public WorkInventorySlot {
            if (index < 0 || index >= MAX_WORK_INVENTORY_SLOTS || count < 1 || count > MAX_STACK_COUNT) {
                throw new IllegalArgumentException("Invalid personal work-inventory slot");
            }
            itemId = requireBounded(itemId, MAX_ITEM_ID_LENGTH, "itemId");
        }
    }

    /** Persisted materials already protected by a longer-running construction task. */
    public record ReservedMaterial(String itemId, int count) {
        public ReservedMaterial {
            if (count < 1 || count > MAX_RESERVED_COUNT) {
                throw new IllegalArgumentException("Invalid protected-material count");
            }
            itemId = requireBounded(itemId, MAX_ITEM_ID_LENGTH, "itemId");
        }
    }

    /** Read-only explanation of the current specialist Work Zone assignment. */
    public record WorkZoneStatus(String roleId, String state, String zoneId, Optional<WorkZoneBoundary> boundary) {
        private static final List<String> STATES = List.of(
                "unassigned", "assignment_mismatch", "missing", "zone_role_mismatch", "other_dimension", "inside", "outside");

        public WorkZoneStatus {
            roleId = requireBounded(roleId, MAX_ROLE_ID_LENGTH, "roleId");
            if (!STATES.contains(state)) {
                throw new IllegalArgumentException("Unknown Work Zone state: " + state);
            }
            zoneId = optionalBounded(zoneId, MAX_ZONE_ID_LENGTH, "zoneId");
            boundary = boundary == null ? Optional.empty() : boundary;
            if (boundary.isPresent() && zoneId.isBlank()) {
                throw new IllegalArgumentException("A Work Zone boundary requires a zone id");
            }
        }
    }

    /** The server-confirmed inclusive boundary of a Work Zone. */
    public record WorkZoneBoundary(String dimensionId, int minimumX, int minimumY, int minimumZ,
                                   int maximumX, int maximumY, int maximumZ) {
        public WorkZoneBoundary {
            dimensionId = requireBounded(dimensionId, MAX_DIMENSION_ID_LENGTH, "dimensionId");
            if (minimumX > maximumX || minimumY > maximumY || minimumZ > maximumZ) {
                throw new IllegalArgumentException("Work Zone minimum must not exceed maximum");
            }
        }
    }

    /** Read-only Guard Post defence demand and construction state for a Guard's trade screen. */
    public record GuardPostStatus(
            String state,
            int managedGolems,
            int defenceDemand,
            int nearbyThreats,
            Optional<GuardPostLocation> post,
            Optional<GuardConstructionProgress> construction
    ) {
        private static final List<String> STATES = List.of(
                "unregistered", "post_unavailable", "defence_needed", "defended", "constructing");

        public GuardPostStatus {
            if (!STATES.contains(state)) {
                throw new IllegalArgumentException("Unknown Guard Post state: " + state);
            }
            if (managedGolems < 0 || managedGolems > MAX_GUARD_COUNT
                    || defenceDemand < 0 || defenceDemand > MAX_GUARD_COUNT
                    || nearbyThreats < 0 || nearbyThreats > MAX_GUARD_COUNT) {
                throw new IllegalArgumentException("Guard Post counts are out of range");
            }
            post = post == null ? Optional.empty() : post;
            construction = construction == null ? Optional.empty() : construction;
            if (post.isEmpty() && (!"unregistered".equals(state) || construction.isPresent())) {
                throw new IllegalArgumentException("Registered Guard Post status requires a post");
            }
        }
    }

    /** Location of the explicitly registered, server-confirmed Guard Post construction pad. */
    public record GuardPostLocation(String villageId, String dimensionId, int padX, int padY, int padZ) {
        public GuardPostLocation {
            villageId = requireBounded(villageId, MAX_ZONE_ID_LENGTH, "villageId");
            dimensionId = requireBounded(dimensionId, MAX_DIMENSION_ID_LENGTH, "dimensionId");
        }
    }

    /** A zero total means the persisted defence order is currently unavailable. */
    public record GuardConstructionProgress(String orderId, int placedSteps, int totalSteps) {
        public GuardConstructionProgress {
            orderId = requireBounded(orderId, MAX_ITEM_ID_LENGTH, "orderId");
            if (placedSteps < 0 || placedSteps > MAX_GUARD_COUNT || totalSteps < 0 || totalSteps > MAX_GUARD_COUNT) {
                throw new IllegalArgumentException("Guard construction progress is out of range");
            }
        }
    }

    public TradeSnapshotPayload {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId cannot be negative");
        }
        Objects.requireNonNull(villagerId, "villagerId");
        offers = List.copyOf(offers == null ? List.of() : offers);
        if (offers.size() > MAX_OFFERS) {
            throw new IllegalArgumentException("Too many trade snapshot offers");
        }
        workInventory = List.copyOf(workInventory == null ? List.of() : workInventory);
        if (workInventory.size() > MAX_WORK_INVENTORY_SLOTS
                || workInventory.stream().map(WorkInventorySlot::index).distinct().count() != workInventory.size()) {
            throw new IllegalArgumentException("Invalid personal work-inventory snapshot");
        }
        reservedMaterials = List.copyOf(reservedMaterials == null ? List.of() : reservedMaterials);
        if (reservedMaterials.size() > MAX_RESERVED_MATERIALS) {
            throw new IllegalArgumentException("Too many protected material entries");
        }
        workZone = workZone == null ? Optional.empty() : workZone;
        guardPost = guardPost == null ? Optional.empty() : guardPost;
    }

    /** Compatibility constructor for an offer-only snapshot. */
    public TradeSnapshotPayload(int containerId, UUID villagerId, List<Offer> offers) {
        this(containerId, villagerId, offers, List.of(), List.of(), Optional.empty(), Optional.empty());
    }

    /** Compatibility constructor for snapshots without Work Zone feedback. */
    public TradeSnapshotPayload(int containerId, UUID villagerId, List<Offer> offers,
                                List<WorkInventorySlot> workInventory, List<ReservedMaterial> reservedMaterials) {
        this(containerId, villagerId, offers, workInventory, reservedMaterials, Optional.empty(), Optional.empty());
    }

    /** Compatibility constructor for snapshots without Guard Post feedback. */
    public TradeSnapshotPayload(int containerId, UUID villagerId, List<Offer> offers,
                                List<WorkInventorySlot> workInventory, List<ReservedMaterial> reservedMaterials,
                                Optional<WorkZoneStatus> workZone) {
        this(containerId, villagerId, offers, workInventory, reservedMaterials, workZone, Optional.empty());
    }

    public static final StreamCodec<FriendlyByteBuf, TradeSnapshotPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.containerId());
                buffer.writeUUID(payload.villagerId());
                buffer.writeVarInt(payload.offers().size());
                for (Offer offer : payload.offers()) {
                    buffer.writeVarInt(offer.index());
                    buffer.writeUtf(offer.itemId(), MAX_ITEM_ID_LENGTH);
                    buffer.writeVarInt(offer.availableStock());
                    buffer.writeVarInt(offer.requiredStock());
                    buffer.writeUtf(offer.source(), MAX_SOURCE_LENGTH);
                    buffer.writeVarInt(offer.progressTicks());
                    buffer.writeVarInt(offer.totalWorkTicks());
                    buffer.writeUtf(offer.blockedReason(), MAX_REASON_LENGTH);
                    buffer.writeVarInt(offer.recipeInputs().size());
                    for (RecipeInput input : offer.recipeInputs()) {
                        buffer.writeUtf(input.itemId(), MAX_ITEM_ID_LENGTH);
                        buffer.writeVarInt(input.count());
                    }
                }
                buffer.writeVarInt(payload.workInventory().size());
                for (WorkInventorySlot slot : payload.workInventory()) {
                    buffer.writeVarInt(slot.index());
                    buffer.writeUtf(slot.itemId(), MAX_ITEM_ID_LENGTH);
                    buffer.writeVarInt(slot.count());
                }
                buffer.writeVarInt(payload.reservedMaterials().size());
                for (ReservedMaterial material : payload.reservedMaterials()) {
                    buffer.writeUtf(material.itemId(), MAX_ITEM_ID_LENGTH);
                    buffer.writeVarInt(material.count());
                }
                buffer.writeBoolean(payload.workZone().isPresent());
                payload.workZone().ifPresent(status -> {
                    buffer.writeUtf(status.roleId(), MAX_ROLE_ID_LENGTH);
                    buffer.writeUtf(status.state(), MAX_REASON_LENGTH);
                    buffer.writeUtf(status.zoneId(), MAX_ZONE_ID_LENGTH);
                    buffer.writeBoolean(status.boundary().isPresent());
                    status.boundary().ifPresent(boundary -> {
                        buffer.writeUtf(boundary.dimensionId(), MAX_DIMENSION_ID_LENGTH);
                        buffer.writeInt(boundary.minimumX());
                        buffer.writeInt(boundary.minimumY());
                        buffer.writeInt(boundary.minimumZ());
                        buffer.writeInt(boundary.maximumX());
                        buffer.writeInt(boundary.maximumY());
                        buffer.writeInt(boundary.maximumZ());
                    });
                });
                buffer.writeBoolean(payload.guardPost().isPresent());
                payload.guardPost().ifPresent(status -> {
                    buffer.writeUtf(status.state(), MAX_REASON_LENGTH);
                    buffer.writeVarInt(status.managedGolems());
                    buffer.writeVarInt(status.defenceDemand());
                    buffer.writeVarInt(status.nearbyThreats());
                    buffer.writeBoolean(status.post().isPresent());
                    status.post().ifPresent(post -> {
                        buffer.writeUtf(post.villageId(), MAX_ZONE_ID_LENGTH);
                        buffer.writeUtf(post.dimensionId(), MAX_DIMENSION_ID_LENGTH);
                        buffer.writeInt(post.padX());
                        buffer.writeInt(post.padY());
                        buffer.writeInt(post.padZ());
                    });
                    buffer.writeBoolean(status.construction().isPresent());
                    status.construction().ifPresent(construction -> {
                        buffer.writeUtf(construction.orderId(), MAX_ITEM_ID_LENGTH);
                        buffer.writeVarInt(construction.placedSteps());
                        buffer.writeVarInt(construction.totalSteps());
                    });
                });
            },
            buffer -> {
                int containerId = buffer.readVarInt();
                UUID villagerId = buffer.readUUID();
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_OFFERS) {
                    throw new IllegalArgumentException("Trade snapshot offer count is out of range");
                }
                List<Offer> offers = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    int offerIndex = buffer.readVarInt();
                    String itemId = buffer.readUtf(MAX_ITEM_ID_LENGTH);
                    int availableStock = buffer.readVarInt();
                    int requiredStock = buffer.readVarInt();
                    String source = buffer.readUtf(MAX_SOURCE_LENGTH);
                    int progressTicks = buffer.readVarInt();
                    int totalWorkTicks = buffer.readVarInt();
                    String blockedReason = buffer.readUtf(MAX_REASON_LENGTH);
                    int inputCount = buffer.readVarInt();
                    if (inputCount < 0 || inputCount > MAX_RECIPE_INPUTS) {
                        throw new IllegalArgumentException("Trade snapshot recipe-input count is out of range");
                    }
                    List<RecipeInput> recipeInputs = new ArrayList<>(inputCount);
                    for (int input = 0; input < inputCount; input++) {
                        recipeInputs.add(new RecipeInput(buffer.readUtf(MAX_ITEM_ID_LENGTH), buffer.readVarInt()));
                    }
                    offers.add(new Offer(
                            offerIndex,
                            itemId,
                            availableStock,
                            requiredStock,
                            source,
                            progressTicks,
                            totalWorkTicks,
                            blockedReason,
                            recipeInputs
                    ));
                }
                int slotCount = buffer.readVarInt();
                if (slotCount < 0 || slotCount > MAX_WORK_INVENTORY_SLOTS) {
                    throw new IllegalArgumentException("Trade snapshot work-inventory count is out of range");
                }
                List<WorkInventorySlot> workInventory = new ArrayList<>(slotCount);
                for (int index = 0; index < slotCount; index++) {
                    workInventory.add(new WorkInventorySlot(buffer.readVarInt(), buffer.readUtf(MAX_ITEM_ID_LENGTH),
                            buffer.readVarInt()));
                }
                int reservedCount = buffer.readVarInt();
                if (reservedCount < 0 || reservedCount > MAX_RESERVED_MATERIALS) {
                    throw new IllegalArgumentException("Trade snapshot protected-material count is out of range");
                }
                List<ReservedMaterial> reservedMaterials = new ArrayList<>(reservedCount);
                for (int index = 0; index < reservedCount; index++) {
                    reservedMaterials.add(new ReservedMaterial(buffer.readUtf(MAX_ITEM_ID_LENGTH), buffer.readVarInt()));
                }
                Optional<WorkZoneStatus> workZone = Optional.empty();
                if (buffer.readBoolean()) {
                    String roleId = buffer.readUtf(MAX_ROLE_ID_LENGTH);
                    String state = buffer.readUtf(MAX_REASON_LENGTH);
                    String zoneId = buffer.readUtf(MAX_ZONE_ID_LENGTH);
                    Optional<WorkZoneBoundary> boundary = Optional.empty();
                    if (buffer.readBoolean()) {
                        boundary = Optional.of(new WorkZoneBoundary(buffer.readUtf(MAX_DIMENSION_ID_LENGTH),
                                buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()));
                    }
                    workZone = Optional.of(new WorkZoneStatus(roleId, state, zoneId, boundary));
                }
                Optional<GuardPostStatus> guardPost = Optional.empty();
                if (buffer.readBoolean()) {
                    String state = buffer.readUtf(MAX_REASON_LENGTH);
                    int managedGolems = buffer.readVarInt();
                    int defenceDemand = buffer.readVarInt();
                    int nearbyThreats = buffer.readVarInt();
                    Optional<GuardPostLocation> post = Optional.empty();
                    if (buffer.readBoolean()) {
                        post = Optional.of(new GuardPostLocation(buffer.readUtf(MAX_ZONE_ID_LENGTH),
                                buffer.readUtf(MAX_DIMENSION_ID_LENGTH), buffer.readInt(), buffer.readInt(), buffer.readInt()));
                    }
                    Optional<GuardConstructionProgress> construction = Optional.empty();
                    if (buffer.readBoolean()) {
                        construction = Optional.of(new GuardConstructionProgress(buffer.readUtf(MAX_ITEM_ID_LENGTH),
                                buffer.readVarInt(), buffer.readVarInt()));
                    }
                    guardPost = Optional.of(new GuardPostStatus(state, managedGolems, defenceDemand, nearbyThreats,
                            post, construction));
                }
                return new TradeSnapshotPayload(containerId, villagerId, offers, workInventory, reservedMaterials, workZone, guardPost);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static String requireBounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be nonblank and no longer than " + maximumLength + " characters");
        }
        return value;
    }

    private static String optionalBounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requireBounded(value, maximumLength, name);
    }
}
