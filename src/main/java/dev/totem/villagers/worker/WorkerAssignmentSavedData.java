package dev.totem.villagers.worker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** World-persistent zone and specialist assignments; lookup never searches unloaded chunks. */
public final class WorkerAssignmentSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final Codec<WorkerAssignmentSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(WorkerAssignmentSavedData::dataVersion),
            WorkZoneRecord.CODEC.listOf().optionalFieldOf("zones", List.of()).forGetter(WorkerAssignmentSavedData::zoneList),
            WorkerAssignment.CODEC.listOf().optionalFieldOf("assignments", List.of()).forGetter(WorkerAssignmentSavedData::assignmentList)
    ).apply(instance, WorkerAssignmentSavedData::new));
    public static final SavedDataType<WorkerAssignmentSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "worker_assignments"),
            WorkerAssignmentSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<UUID, WorkZoneRecord> zones = new LinkedHashMap<>();
    private final Map<UUID, WorkerAssignment> assignments = new LinkedHashMap<>();

    public WorkerAssignmentSavedData() {
        this(DATA_VERSION, List.of(), List.of());
    }

    private WorkerAssignmentSavedData(int dataVersion, List<WorkZoneRecord> persistedZones, List<WorkerAssignment> persistedAssignments) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        persistedZones.forEach(zone -> zones.putIfAbsent(zone.id(), zone));
        persistedAssignments.forEach(assignment -> assignments.putIfAbsent(assignment.villagerId(), assignment));
    }

    public static WorkerAssignmentSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized WorkZoneRecord createZone(String roleId, WorkZone zone) {
        WorkZoneRecord record = new WorkZoneRecord(UUID.randomUUID(), roleId, zone);
        zones.put(record.id(), record);
        setDirty();
        return record;
    }

    public synchronized Optional<WorkZoneRecord> getZone(UUID zoneId) {
        return Optional.ofNullable(zones.get(zoneId));
    }

    /**
     * Extends one persisted Miner shaft by exactly one block at its lower
     * boundary while preserving its identity, owner, dimension and horizontal
     * permission boundary.
     */
    public synchronized boolean extendMinerZoneDownward(UUID zoneId, int newMinimumY) {
        WorkZoneRecord current = zones.get(zoneId);
        if (current == null || !WorkerProfessionRegistry.MINER.id().equals(current.roleId())
                || newMinimumY != current.zone().minimum().y() - 1) {
            return false;
        }
        WorkZone zone = current.zone();
        WorkZone extended = new WorkZone(zone.ownerId(), zone.dimensionId(),
                new BlockCoordinate(zone.minimum().x(), newMinimumY, zone.minimum().z()), zone.maximum());
        zones.put(zoneId, new WorkZoneRecord(current.id(), current.roleId(), extended));
        setDirty();
        return true;
    }

    /** The zone owner must match the villager-assignment operator, preventing cross-owner zone borrowing. */
    public synchronized boolean assignZone(UUID actorId, WorkerAssignment assignment, UUID zoneId) {
        WorkZoneRecord zone = zones.get(zoneId);
        if (zone == null || !zone.zone().ownerId().equals(actorId) || !zone.roleId().equals(assignment.roleId())) {
            return false;
        }
        WorkerAssignment updated = assignment.withWorkZone(Optional.of(zoneId));
        WorkerAssignment previous = assignments.put(updated.villagerId(), updated);
        if (!updated.equals(previous)) {
            setDirty();
        }
        return true;
    }

    public synchronized void putAssignment(WorkerAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        WorkerAssignment previous = assignments.put(assignment.villagerId(), assignment);
        if (!assignment.equals(previous)) {
            setDirty();
        }
    }

    public synchronized Optional<WorkerAssignment> getAssignment(UUID villagerId) {
        return Optional.ofNullable(assignments.get(villagerId));
    }

    /** Removes a dead worker's specialist claim so its configured zone can be staffed again. */
    public synchronized void removeAssignment(UUID villagerId) {
        if (assignments.remove(villagerId) != null) {
            setDirty();
        }
    }

    /** Test and maintenance hook; production zones are normally retained by their operator. */
    public synchronized void removeZone(UUID zoneId) {
        if (zones.remove(zoneId) != null) {
            assignments.entrySet().removeIf(entry -> entry.getValue().workZoneId().filter(zoneId::equals).isPresent());
            setDirty();
        }
    }

    public synchronized Map<UUID, WorkZoneRecord> zoneSnapshot() {
        return Map.copyOf(zones);
    }

    public synchronized Map<UUID, WorkerAssignment> assignmentSnapshot() {
        return Map.copyOf(assignments);
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<WorkZoneRecord> zoneList() {
        return List.copyOf(zones.values());
    }

    private synchronized List<WorkerAssignment> assignmentList() {
        return List.copyOf(assignments.values());
    }
}
