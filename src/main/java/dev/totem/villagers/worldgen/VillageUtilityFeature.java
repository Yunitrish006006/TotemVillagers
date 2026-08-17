package dev.totem.villagers.worldgen;

import dev.totem.villagers.content.TotemVillagerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The one required utility attachment for every vanilla village centre.
 *
 * <p>The custom jigsaw pool element uses the shared placement routine below
 * while the village is assembled, so the lumberyard and the 5 x 5 mineshaft
 * are part of the same structure placement pass as the houses and roads. The
 * runtime only discovers the resulting stations and assigns their finite work
 * zones; it never has to construct a replacement facility for a newly
 * generated village.</p>
 */
public final class VillageUtilityFeature extends Feature<NoneFeatureConfiguration> {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("totem", "village_utilities");
    public static final Feature<NoneFeatureConfiguration> INSTANCE = Registry.register(BuiltInRegistries.FEATURE,
            ResourceKey.create(Registries.FEATURE, ID), new VillageUtilityFeature());

    private static final int MINE_RADIUS = 2;
    private static final int MINE_DEPTH = 16;
    private static final int[][] SPIRAL = {
            {-2, 0}, {-2, 1}, {-2, 2}, {-1, 2},
            {0, 2}, {1, 2}, {2, 2}, {2, 1},
            {2, 0}, {2, -1}, {2, -2}, {1, -2},
            {0, -2}, {-1, -2}, {-2, -2}, {-2, -1}
    };

    private VillageUtilityFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    /** Forces static feature registration during common mod initialisation. */
    public static void register() {
        // Static registration above intentionally owns the registry lifetime.
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        BlockPos yard = context.origin();
        placeLumberyard(context.level(), yard, BoundingBox.infinite());
        return placeMine(context.level(), yard.east(9), BoundingBox.infinite());
    }

    /**
     * Places the portion of the fixed facility owned by a structure-generation
     * chunk.  A jigsaw piece is post-processed once per intersecting chunk, so
     * clipping each block here (rather than asking a one-shot feature to place
     * the whole facility) lets the shaft safely reach all sixteen levels below
     * the surface entrance.
     */
    public static boolean placeLumberyard(WorldGenLevel level, BlockPos yard, BoundingBox clip) {
        buildLumberyard(level, yard, clip);
        return true;
    }

    /** Places the mine building and all of its visible shaft segment in the current chunk. */
    public static boolean placeMine(WorldGenLevel level, BlockPos furnace, BoundingBox clip) {
        if (furnace.getY() - MINE_DEPTH - 4 < level.getMinY()) {
            return false;
        }
        buildMine(level, furnace, mineDirection(), clip);
        return true;
    }

    /** The mature oak is intentionally fixed relative to its Woodcutter marker. */
    public static BlockPos treeBaseFromWoodcutter(BlockPos woodcutter) {
        return woodcutter.west(3).north();
    }

    /** The fixed Lumberyard also carries the fourth-priority core Toolsmith station. */
    public static BlockPos smithingTableFromWoodcutter(BlockPos woodcutter) {
        return woodcutter.south(2);
    }

    /** The fixed mine always descends east from its Furnace entrance. */
    public static Direction mineDirection() {
        return Direction.EAST;
    }

    public static BlockPos mineCenter(BlockPos furnace) {
        return furnace.relative(mineDirection(), MINE_RADIUS + 1);
    }

    public static BlockPos mineLanding(BlockPos furnace, int step) {
        return mineLanding(furnace, mineDirection(), step);
    }

    /** Resolves any persisted spiral step, including layers added after world generation. */
    public static BlockPos mineLanding(BlockPos furnace, Direction direction, int step) {
        int[] coordinate = spiralCoordinate(step);
        return furnace.relative(direction, MINE_RADIUS + 1).relative(direction, coordinate[0])
                .relative(direction.getClockWise(), coordinate[1]).below(step);
    }

    public static Direction mineInwardDirection(Direction direction, int step) {
        return inwardDirection(direction, spiralCoordinate(step));
    }

    public static Direction mineOutwardDirection(Direction direction, int step) {
        return mineInwardDirection(direction, step).getOpposite();
    }

    public static Direction mineDescentDirection(Direction direction, int step) {
        int[] current = spiralCoordinate(step);
        int[] next = spiralCoordinate(step + 1);
        int forwardDelta = next[0] - current[0];
        if (forwardDelta != 0) {
            return forwardDelta > 0 ? direction : direction.getOpposite();
        }
        return next[1] - current[1] > 0 ? direction.getClockWise() : direction.getClockWise().getOpposite();
    }

    /** The side entrance shared by the Oak and Mangrove mine palettes. */
    public static BlockPos mineGate(BlockPos furnace) {
        Direction direction = mineDirection();
        return mineCenter(furnace).relative(direction.getOpposite(), 3)
                .relative(direction.getClockWise().getOpposite());
    }

    public static boolean hasGuardRail(int step) {
        return step % 4 != 2;
    }

    private static void buildLumberyard(WorldGenLevel level, BlockPos yard, BoundingBox clip) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                set(level, clip, yard.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState());
                set(level, clip, yard.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                set(level, clip, yard.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x : new int[]{-3, 3}) {
            for (int z : new int[]{-3, 3}) {
                for (int y = 0; y <= 4; y++) {
                    set(level, clip, yard.offset(x, y, z), Blocks.OAK_LOG.defaultBlockState());
                }
            }
        }
        BlockPos treeBase = treeBaseFromWoodcutter(yard.east(2));
        set(level, clip, treeBase.below(), Blocks.DIRT.defaultBlockState());
        for (int y = 0; y < 4; y++) {
            set(level, clip, treeBase.above(y), Blocks.OAK_LOG.defaultBlockState());
        }
        BlockPos canopy = treeBase.above(4);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) + Math.abs(z) <= 3) {
                    set(level, clip, canopy.offset(x, 0, z), Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
        }
        set(level, clip, canopy.above(), Blocks.OAK_LEAVES.defaultBlockState());
        BlockPos woodcutter = yard.east(2);
        set(level, clip, woodcutter, TotemVillagerBlocks.WOODCUTTER.defaultBlockState());
        set(level, clip, woodcutter.above(), Blocks.AIR.defaultBlockState());
        BlockPos smithingTable = smithingTableFromWoodcutter(woodcutter);
        set(level, clip, smithingTable, Blocks.SMITHING_TABLE.defaultBlockState());
        set(level, clip, smithingTable.above(), Blocks.AIR.defaultBlockState());
        BlockPos fibreTrellis = woodcutter.north(2);
        for (int y = 0; y <= 2; y++) {
            set(level, clip, fibreTrellis.above(y), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        }
        BlockState attachedVine = Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true);
        set(level, clip, fibreTrellis.east().above(), attachedVine);
        set(level, clip, fibreTrellis.east().above(2), attachedVine);
    }

    private static void buildMine(WorldGenLevel level, BlockPos furnace, Direction direction, BoundingBox clip) {
        clearMineHead(level, furnace, direction, clip);
        buildMineShell(level, furnace, clip);
        set(level, clip, furnace.below(), Blocks.COBBLESTONE.defaultBlockState());
        set(level, clip, furnace, Blocks.FURNACE.defaultBlockState());
        set(level, clip, furnace.above(), Blocks.AIR.defaultBlockState());

        BlockPos center = mineCenter(furnace);
        for (int depth = -2; depth < MINE_DEPTH; depth++) {
            set(level, clip, center.below(depth), Blocks.AIR.defaultBlockState());
        }
        for (int step = 0; step < MINE_DEPTH; step++) {
            BlockPos landing = mineLanding(furnace, step);
            BlockPos floor = landing.below();
            BlockPos rail = landing.relative(inwardDirection(direction, SPIRAL[step]));
            BlockPos face = landing.relative(outwardDirection(direction, SPIRAL[step]));
            set(level, clip, landing, Blocks.AIR.defaultBlockState());
            set(level, clip, landing.above(), Blocks.AIR.defaultBlockState());
            set(level, clip, landing.above(2), Blocks.AIR.defaultBlockState());
            set(level, clip, landing.above(3), Blocks.COBBLESTONE.defaultBlockState());
            set(level, clip, floor, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, mineDescentDirection(direction, step).getOpposite()));
            // The first outer mining face is the Furnace itself.  It must
            // remain a workstation, just as the runtime starter leaves that
            // entry face intact for the Miner.
            if (!face.equals(furnace)) {
                set(level, clip, face, Blocks.STONE.defaultBlockState());
            }
            if (hasGuardRail(step)) {
                set(level, clip, rail.below(), Blocks.COBBLESTONE.defaultBlockState());
                set(level, clip, rail, Blocks.OAK_FENCE.defaultBlockState());
            }
        }
        buildMineHead(level, furnace, direction, clip);
    }

    /** Clears the seven-block shelter interior plus only the height occupied by its wider eaves. */
    private static void clearMineHead(WorldGenLevel level, BlockPos furnace, Direction direction, BoundingBox clip) {
        BlockPos center = mineCenter(furnace);
        Direction sideways = direction.getClockWise();
        for (int forward = -4; forward <= 4; forward++) {
            for (int side = -4; side <= 4; side++) {
                int firstY = Math.max(Math.abs(forward), Math.abs(side)) == 4 ? 4 : 0;
                for (int y = firstY; y <= 5; y++) {
                    set(level, clip, center.relative(direction, forward).relative(sideways, side).above(y),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * Builds a compact timber-framed mine shelter around the open shaft. The
     * deliberate surface footprint is limited to the advertised jigsaw box;
     * the broad water/void casing remains underground-only.
     */
    private static void buildMineHead(WorldGenLevel level, BlockPos furnace, Direction direction, BoundingBox clip) {
        BlockPos center = mineCenter(furnace);
        Direction sideways = direction.getClockWise();

        // A mixed cobblestone perimeter reads as a laid foundation rather than
        // the solid stone bunker used by the previous design.
        for (int forward = -3; forward <= 3; forward++) {
            for (int side = -3; side <= 3; side++) {
                if (Math.max(Math.abs(forward), Math.abs(side)) != 3) {
                    continue;
                }
                BlockState foundation = Math.abs(forward) == 3 && Math.abs(side) == 3
                        ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                        : Blocks.COBBLESTONE.defaultBlockState();
                set(level, clip, mineHeadOffset(center, direction, forward, -1, side), foundation);
            }
        }

        // The western side entrance passes beside the Furnace and joins the
        // first spiral stair without covering any part of the hollow centre.
        set(level, clip, mineHeadOffset(center, direction, -2, -1, -1), Blocks.COBBLESTONE.defaultBlockState());
        BlockPos gate = mineGate(furnace);
        // Villagers cannot open fence gates through vanilla navigation. Keep
        // the safety gate visibly folded open so the Miner can actually walk
        // from the village deck to the first exposed face in the shaft.
        set(level, clip, gate, Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, direction)
                .setValue(BlockStateProperties.OPEN, true));

        BlockState post = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
        for (int forward : new int[]{-3, 3}) {
            for (int side : new int[]{-3, 3}) {
                for (int y = 0; y <= 3; y++) {
                    set(level, clip, mineHeadOffset(center, direction, forward, y, side), post);
                }
            }
        }

        BlockState forwardBeam = post.setValue(RotatedPillarBlock.AXIS, direction.getAxis());
        BlockState sidewaysBeam = post.setValue(RotatedPillarBlock.AXIS, sideways.getAxis());
        for (int forward = -2; forward <= 2; forward++) {
            set(level, clip, mineHeadOffset(center, direction, forward, 3, -3), forwardBeam);
            set(level, clip, mineHeadOffset(center, direction, forward, 3, 3), forwardBeam);
        }
        for (int side = -2; side <= 2; side++) {
            set(level, clip, mineHeadOffset(center, direction, -3, 3, side), sidewaysBeam);
            set(level, clip, mineHeadOffset(center, direction, 3, 3, side), sidewaysBeam);
        }

        // Fence the exposed rim, leaving the Furnace and side gate as the only
        // entrance. Explicit connection states keep the frame attractive even
        // during structure placement, where neighbour updates are suppressed.
        BlockState forwardFence = fenceAlong(direction);
        BlockState sidewaysFence = fenceAlong(sideways);
        for (int offset = -2; offset <= 2; offset++) {
            set(level, clip, mineHeadOffset(center, direction, 3, 0, offset), sidewaysFence);
            set(level, clip, mineHeadOffset(center, direction, offset, 0, -3), forwardFence);
            set(level, clip, mineHeadOffset(center, direction, offset, 0, 3), forwardFence);
            if (offset != -1 && offset != 0) {
                set(level, clip, mineHeadOffset(center, direction, -3, 0, offset), sidewaysFence);
            }
        }

        // A shallow pitched roof replaces the old flat lid. Outward-facing
        // stairs make the broad eaves, then a raised slab ring leaves a 3 x 3
        // skylight over the hoist.
        for (int offset = -3; offset <= 3; offset++) {
            set(level, clip, mineHeadOffset(center, direction, offset, 4, -4),
                    Blocks.SPRUCE_STAIRS.defaultBlockState()
                            .setValue(StairBlock.FACING, sideways.getOpposite()));
            set(level, clip, mineHeadOffset(center, direction, offset, 4, 4),
                    Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, sideways));
            set(level, clip, mineHeadOffset(center, direction, -4, 4, offset),
                    Blocks.SPRUCE_STAIRS.defaultBlockState()
                            .setValue(StairBlock.FACING, direction.getOpposite()));
            set(level, clip, mineHeadOffset(center, direction, 4, 4, offset),
                    Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, direction));
        }
        for (int forward = -3; forward <= 3; forward++) {
            for (int side = -3; side <= 3; side++) {
                if (Math.abs(forward) <= 1 && Math.abs(side) <= 1) {
                    continue;
                }
                set(level, clip, mineHeadOffset(center, direction, forward, 5, side),
                        Blocks.SPRUCE_SLAB.defaultBlockState());
            }
        }
        for (int side = -2; side <= 2; side++) {
            set(level, clip, mineHeadOffset(center, direction, 0, 5, side), sidewaysBeam);
        }
        set(level, clip, center.above(4), Blocks.IRON_CHAIN.defaultBlockState());
        set(level, clip, center.above(3), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    /**
     * Supports only the underground shaft. Existing terrain remains intact;
     * stone is added solely where the planned shaft crosses original air or a
     * fluid block, which closes underwater voids without making a surface
     * bunker or replacing surrounding soil and stone.
     */
    private static void buildMineShell(WorldGenLevel level, BlockPos furnace, BoundingBox clip) {
        for (int x = -1; x <= 7; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -MINE_DEPTH; y < 0; y++) {
                    fillVoidOrFluidWithStone(level, clip, furnace.offset(x, y, z));
                }
            }
        }
    }

    private static Direction inwardDirection(Direction forward, int[] coordinate) {
        Direction sideways = forward.getClockWise();
        if (coordinate[0] == -MINE_RADIUS) {
            return forward;
        }
        if (coordinate[0] == MINE_RADIUS) {
            return forward.getOpposite();
        }
        if (coordinate[1] == -MINE_RADIUS) {
            return sideways;
        }
        return sideways.getOpposite();
    }

    private static Direction outwardDirection(Direction forward, int[] coordinate) {
        return inwardDirection(forward, coordinate).getOpposite();
    }

    private static int[] spiralCoordinate(int step) {
        if (step < 0) {
            throw new IllegalArgumentException("Mine step must not be negative");
        }
        return SPIRAL[step % SPIRAL.length];
    }

    private static BlockPos mineHeadOffset(
            BlockPos center, Direction forward, int forwardOffset, int yOffset, int sideOffset
    ) {
        return center.relative(forward, forwardOffset)
                .relative(forward.getClockWise(), sideOffset)
                .above(yOffset);
    }

    private static BlockState fenceAlong(Direction line) {
        BlockState state = Blocks.OAK_FENCE.defaultBlockState();
        return line.getAxis() == Direction.Axis.X
                ? state.setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true)
                : state.setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.SOUTH, true);
    }

    private static void set(WorldGenLevel level, BoundingBox clip, BlockPos position, BlockState state) {
        if (clip.isInside(position) && level.ensureCanWrite(position)) {
            level.setBlock(position, state, 2);
        }
    }

    private static void fillVoidOrFluidWithStone(WorldGenLevel level, BoundingBox clip, BlockPos position) {
        if (!clip.isInside(position) || !level.ensureCanWrite(position)) {
            return;
        }
        BlockState existing = level.getBlockState(position);
        if (existing.isAir() || !existing.getFluidState().isEmpty()) {
            level.setBlock(position, Blocks.STONE.defaultBlockState(), 2);
        }
    }
}
