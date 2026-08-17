package dev.totem.villagers.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;

/**
 * A jigsaw pool element with the real footprint of either the Lumberyard or
 * the Mine.
 * Vanilla's {@code FeaturePoolElement} advertises a zero-sized box; that is
 * fine for one-block decoration but causes a multi-chunk facility to be
 * post-processed only in its anchor chunk. This element gives the jigsaw
 * piece its full horizontal bounds and writes its own clipped portion, so each
 * intersecting structure chunk receives the appropriate surface and
 * underground blocks during normal village generation. Chunk generation is
 * already full-height, therefore the shaft must not enlarge the jigsaw's
 * vertical box: a rigid jigsaw connection would otherwise align that box's
 * bottom and lift the surface entrance above its town-centre connector.
 */
public final class VillageUtilityPoolElement extends StructurePoolElement {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("totem", "village_utilities");
    public static final MapCodec<VillageUtilityPoolElement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            com.mojang.serialization.Codec.STRING.fieldOf("facility")
                    .xmap(Facility::fromSerializedName, Facility::serializedName)
                    .forGetter(element -> element.facility),
            projectionCodec()
    ).apply(instance, VillageUtilityPoolElement::new));
    public static final StructurePoolElementType<VillageUtilityPoolElement> TYPE = Registry.register(
            BuiltInRegistries.STRUCTURE_POOL_ELEMENT,
            ResourceKey.create(Registries.STRUCTURE_POOL_ELEMENT, ID),
            () -> CODEC
    );

    private static final Identifier DEFAULT_JIGSAW_NAME = Identifier.withDefaultNamespace("bottom");
    private final Facility facility;
    private final CompoundTag defaultJigsawNbt;

    private VillageUtilityPoolElement(Facility facility, StructureTemplatePool.Projection projection) {
        super(projection);
        this.facility = facility;
        this.defaultJigsawNbt = defaultJigsawNbt();
    }

    /** Forces pool-element type registration during common mod initialisation. */
    public static void register() {
        // Static registration above intentionally owns the registry lifetime.
    }

    @Override
    public Vec3i getSize(StructureTemplateManager manager, Rotation rotation) {
        return switch (facility) {
            case LUMBERYARD -> new Vec3i(7, 6, 7);
            case MINE -> new Vec3i(9, 6, 9);
            case MANGROVE_VILLAGE -> new Vec3i(MangroveVillageFeature.HORIZONTAL_RADIUS * 2 + 1,
                    MangroveVillageFeature.ADVERTISED_HEIGHT, MangroveVillageFeature.HORIZONTAL_RADIUS * 2 + 1);
        };
    }

    @Override
    public List<StructureTemplate.JigsawBlockInfo> getShuffledJigsawBlocks(StructureTemplateManager manager,
                                                                            BlockPos position,
                                                                            Rotation rotation,
                                                                            RandomSource random) {
        StructureTemplate.StructureBlockInfo info = new StructureTemplate.StructureBlockInfo(position,
                Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION,
                        FrontAndTop.fromFrontAndTop(Direction.DOWN, Direction.SOUTH)), defaultJigsawNbt);
        return List.of(StructureTemplate.JigsawBlockInfo.of(info));
    }

    @Override
    public BoundingBox getBoundingBox(StructureTemplateManager manager, BlockPos position, Rotation rotation) {
        if (facility == Facility.LUMBERYARD) {
            return new BoundingBox(position.getX() - 3, position.getY(), position.getZ() - 3,
                    position.getX() + 3, position.getY() + 5, position.getZ() + 3);
        }
        if (facility == Facility.MANGROVE_VILLAGE) {
            // The footprint is square, so the random start-piece rotation does
            // not change clipping or structure identity.
            return MangroveVillageFeature.boundingBox(position);
        }
        return new BoundingBox(position.getX() - 1, position.getY(), position.getZ() - 4,
                position.getX() + 7, position.getY() + 5, position.getZ() + 4);
    }

    @Override
    public boolean place(StructureTemplateManager manager, WorldGenLevel level, StructureManager structureManager,
                         ChunkGenerator generator, BlockPos position, BlockPos pivot, Rotation rotation,
                         BoundingBox bounds, RandomSource random, LiquidSettings liquidSettings, boolean keepJigsaws) {
        return switch (facility) {
            case LUMBERYARD -> VillageUtilityFeature.placeLumberyard(level, position, bounds);
            case MINE -> VillageUtilityFeature.placeMine(level, position, bounds);
            case MANGROVE_VILLAGE -> MangroveVillageFeature.place(level, position, bounds);
        };
    }

    @Override
    public StructurePoolElementType<?> getType() {
        return TYPE;
    }

    @Override
    public String toString() {
        return "VillageUtilityPoolElement{" + facility.serializedName() + "}";
    }

    private static CompoundTag defaultJigsawNbt() {
        CompoundTag tag = new CompoundTag();
        tag.store(JigsawBlockEntity.NAME, Identifier.CODEC, DEFAULT_JIGSAW_NAME);
        tag.putString(JigsawBlockEntity.FINAL_STATE, "minecraft:air");
        tag.store(JigsawBlockEntity.POOL, JigsawBlockEntity.POOL_CODEC,
                ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.withDefaultNamespace("empty")));
        tag.store(JigsawBlockEntity.TARGET, Identifier.CODEC, JigsawBlockEntity.EMPTY_ID);
        tag.store(JigsawBlockEntity.JOINT, JigsawBlockEntity.JointType.CODEC,
                JigsawBlockEntity.JointType.ROLLABLE);
        return tag;
    }

    private enum Facility {
        LUMBERYARD("lumberyard"),
        MINE("mine"),
        MANGROVE_VILLAGE("mangrove_village");

        private final String serializedName;

        Facility(String serializedName) {
            this.serializedName = serializedName;
        }

        private String serializedName() {
            return serializedName;
        }

        private static Facility fromSerializedName(String value) {
            for (Facility facility : values()) {
                if (facility.serializedName.equals(value)) {
                    return facility;
                }
            }
            throw new IllegalArgumentException("Unknown village facility " + value);
        }
    }
}
