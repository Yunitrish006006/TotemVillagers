package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Data-driven definition of one sell-side output and its legitimate production paths.
 * A source path is always explicit so a workshop can never turn a deposited finished
 * item directly into merchant stock.
 */
public record WorkOrder(
        String id,
        String professionId,
        ItemAmount output,
        List<ItemAmount> requiredInputs,
        Set<WorkSource> allowedSources,
        String worldTargetTag,
        String worldTargetEntityType,
        String worldReplantBlockId,
        String outputComponentPatch,
        int workTicks,
        int stockCap
) {
    private static final Codec<Set<WorkSource>> SOURCE_SET_CODEC = WorkSource.CODEC.listOf()
            .xmap(Set::copyOf, List::copyOf);

    public static final Codec<WorkOrder> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(WorkOrder::id),
            Codec.STRING.fieldOf("profession").forGetter(WorkOrder::professionId),
            ItemAmount.CODEC.fieldOf("output").forGetter(WorkOrder::output),
            ItemAmount.CODEC.listOf().optionalFieldOf("required_inputs", List.of()).forGetter(WorkOrder::requiredInputs),
            SOURCE_SET_CODEC.fieldOf("sources").forGetter(WorkOrder::allowedSources),
            Codec.STRING.optionalFieldOf("world_target_tag", "").forGetter(WorkOrder::worldTargetTag),
            Codec.STRING.optionalFieldOf("world_target_entity_type", "").forGetter(WorkOrder::worldTargetEntityType),
            Codec.STRING.optionalFieldOf("world_replant_block", "").forGetter(WorkOrder::worldReplantBlockId),
            Codec.STRING.optionalFieldOf("output_component_patch", "").forGetter(WorkOrder::outputComponentPatch),
            Codec.INT.fieldOf("work_ticks").forGetter(WorkOrder::workTicks),
            Codec.INT.fieldOf("stock_cap").forGetter(WorkOrder::stockCap)
    ).apply(instance, WorkOrder::new));

    public WorkOrder(String id, String professionId, ItemAmount output, List<ItemAmount> requiredInputs,
                     Set<WorkSource> allowedSources, String worldTargetTag, int workTicks, int stockCap) {
        this(id, professionId, output, requiredInputs, allowedSources, worldTargetTag, "", "", "", workTicks, stockCap);
    }

    public WorkOrder {
        requireIdentifier(id, "id");
        requireIdentifier(professionId, "professionId");
        Objects.requireNonNull(output, "output");
        requiredInputs = List.copyOf(requiredInputs);
        allowedSources = Set.copyOf(allowedSources);
        worldTargetTag = worldTargetTag == null ? "" : worldTargetTag;
        worldTargetEntityType = worldTargetEntityType == null ? "" : worldTargetEntityType;
        worldReplantBlockId = worldReplantBlockId == null ? "" : worldReplantBlockId;
        outputComponentPatch = outputComponentPatch == null ? "" : outputComponentPatch;
        new StockVariantKey(output.itemId(), outputComponentPatch);
        if (allowedSources.isEmpty()) {
            throw new IllegalArgumentException("A work order needs at least one allowed source");
        }
        if (workTicks < 1) {
            throw new IllegalArgumentException("workTicks must be positive");
        }
        if (stockCap < output.count()) {
            throw new IllegalArgumentException("stockCap must hold at least one produced output");
        }
        if ((allowedSources.contains(WorkSource.WORKSHOP) || allowedSources.contains(WorkSource.ENCHANTING))
                && requiredInputs.isEmpty()) {
            throw new IllegalArgumentException("Material-backed work must consume raw inputs");
        }
        if (allowedSources.contains(WorkSource.WORLD) && worldTargetTag.isBlank() && worldTargetEntityType.isBlank()) {
            throw new IllegalArgumentException("World work must declare an eligible block tag or entity type");
        }
        if (!worldTargetTag.isBlank() && !worldTargetEntityType.isBlank()) {
            throw new IllegalArgumentException("World work may target either blocks or entities, not both");
        }
        if (!worldTargetEntityType.isBlank()) {
            requireIdentifier(worldTargetEntityType, "worldTargetEntityType");
        }
        if (!worldReplantBlockId.isBlank()) {
            requireIdentifier(worldReplantBlockId, "worldReplantBlockId");
        }
        boolean lumberjackWorldWork = "totem:lumberjack".equals(professionId) && allowedSources.contains(WorkSource.WORLD);
        if (lumberjackWorldWork && worldTargetTag.isBlank()) {
            throw new IllegalArgumentException("Lumberjack world work must target blocks");
        }
        if (lumberjackWorldWork && worldReplantBlockId.isBlank()) {
            throw new IllegalArgumentException("Lumberjack world work must declare a replant block");
        }
        if (!worldReplantBlockId.isBlank() && !lumberjackWorldWork) {
            throw new IllegalArgumentException("Only Lumberjack world work may declare a replant block");
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(field + " must be a namespaced identifier");
        }
    }

    public StockVariantKey outputKey() {
        return new StockVariantKey(output.itemId(), outputComponentPatch);
    }

    /** Keeps an order's authorisation and material budget while binding a newly generated component variant. */
    public WorkOrder withOutputComponentPatch(String componentPatch) {
        return new WorkOrder(id, professionId, output, requiredInputs, allowedSources, worldTargetTag,
                worldTargetEntityType, worldReplantBlockId, componentPatch, workTicks, stockCap);
    }

    /** Binds a constrained generated output whose item identity also changes, such as an explorer filled map. */
    public WorkOrder withOutputVariant(ItemAmount variantOutput, String componentPatch) {
        return new WorkOrder(id, professionId, variantOutput, requiredInputs, allowedSources, worldTargetTag,
                worldTargetEntityType, worldReplantBlockId, componentPatch, workTicks, stockCap);
    }

    /** Exact item, component, and count check used before a work action can mint stock. */
    public boolean matchesOutput(ItemStack stack, HolderLookup.Provider registries) {
        return !stack.isEmpty() && stack.getCount() == output.count()
                && outputKey().equals(StockVariantKey.fromStack(stack, registries));
    }
}
