package dev.totem.villagers.worldgen;

import dev.totem.villagers.content.TotemVillagerBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Code-native first playable Mangrove village.
 *
 * <p>The settlement is deliberately raised and styled as a Southeast-Asian
 * water village: steep bamboo roofs, framed longhouses, open working sheds,
 * lantern piers and rooted stilts connect the physical Fisherman, Lumberjack,
 * Toolsmith and Miner economy. Only this bounded footprint is cleared, leaving
 * the surrounding swamp and its trees intact.</p>
 */
public final class MangroveVillageFeature {
    public static final Identifier STRUCTURE_ID = Identifier.fromNamespaceAndPath("totem", "mangrove_village");
    public static final int HORIZONTAL_RADIUS = 36;
    public static final int ADVERTISED_HEIGHT = 48;
    private static final int SUPPORT_DEPTH = 12;
    private static final int CORE_BED_COUNT = 4;
    private static final int MIN_OPTIONAL_RESIDENCES = 3;
    private static final int MAX_OPTIONAL_RESIDENCES = 6;
    private static final long LAYOUT_SALT = 0x6A09E667F3BCC909L;

    private MangroveVillageFeature() {
    }

    public static BoundingBox boundingBox(BlockPos origin) {
        return new BoundingBox(origin.getX() - HORIZONTAL_RADIUS, origin.getY(), origin.getZ() - HORIZONTAL_RADIUS,
                origin.getX() + HORIZONTAL_RADIUS, origin.getY() + ADVERTISED_HEIGHT - 1,
                origin.getZ() + HORIZONTAL_RADIUS);
    }

    /**
     * Keeps the boardwalk above ordinary sea level without sampling another
     * chunk's heightmap during structure post-processing. Mangrove villages
     * start at Y=63, so the normal deck is Y=66; the sea-level guard also
     * keeps this safe for custom dimensions that retain the structure.
     */
    public static int deckY(WorldGenLevel level, BlockPos origin) {
        return Math.max(origin.getY() + 3, level.getSeaLevel() + 2);
    }

    public static boolean place(WorldGenLevel level, BlockPos origin, BoundingBox clip) {
        return placeForWorldSeed(level, origin, clip, level.getSeed());
    }

    /**
     * Places the same settlement layout that {@code worldSeed} selects at the
     * supplied origin. The explicit seed entry point keeps visual fixtures
     * stable without making natural villages repeat the same arrangement.
     */
    public static boolean placeForWorldSeed(WorldGenLevel level, BlockPos origin, BoundingBox clip, long worldSeed) {
        VillageLayout layout = createLayout(worldSeed, origin);
        int deckY = deckY(level, origin);

        // A patterned central pavilion joins a dense three-wide housing spine,
        // western fish dock and eastern production pier.
        plaza(level, origin, deckY, clip);
        boardwalk(level, origin, -1, 1, -18, 18, deckY, clip);
        boardwalk(level, origin, -21, 16, -1, 1, deckY, clip);
        boardwalk(level, origin, -13, 16, 10, 12, deckY, clip);
        boardwalk(level, origin, -13, -7, -9, 1, deckY, clip);

        for (int x : new int[]{-20, -16, -12, -8, -4, 0, 4, 8, 12, 16}) {
            stilt(level, origin.offset(x, deckY - origin.getY(), 0), deckY, clip);
        }
        for (int z : new int[]{-16, -12, -8, 8, 12, 16}) {
            stilt(level, origin.offset(0, deckY - origin.getY(), z), deckY, clip);
        }

        buildLonghouse(level, origin.offset(0, 0, -12), deckY, Direction.SOUTH, false, clip);
        buildLonghouse(level, origin.offset(0, 0, 13), deckY, Direction.NORTH, true, clip);
        placeBed(level, origin.offset(-2, deckY + 1 - origin.getY(), -12), Direction.NORTH,
                Blocks.BED.cyan().defaultBlockState(), clip);
        placeBed(level, origin.offset(2, deckY + 1 - origin.getY(), -12), Direction.NORTH,
                Blocks.BED.lightBlue().defaultBlockState(), clip);
        placeBed(level, origin.offset(-2, deckY + 1 - origin.getY(), 12), Direction.SOUTH,
                Blocks.BED.cyan().defaultBlockState(), clip);
        placeBed(level, origin.offset(2, deckY + 1 - origin.getY(), 12), Direction.SOUTH,
                Blocks.BED.lightBlue().defaultBlockState(), clip);

        // Fisherman's smokehouse: Barrel is the real vanilla POI; the nearby
        // Campfire is a separate production condition rather than a fake POI.
        BlockPos fisherHut = origin.offset(-10, 0, -6);
        buildFishingHouse(level, fisherHut, deckY, clip);
        set(level, clip, origin.offset(-10, deckY + 1 - origin.getY(), -6), Blocks.BARREL.defaultBlockState());
        set(level, clip, origin.offset(-10, deckY + 1 - origin.getY(), -2), Blocks.CAMPFIRE.defaultBlockState());
        fishingBasin(level, origin, deckY, clip);

        buildBellPavilion(level, origin, deckY, clip);
        set(level, clip, origin.offset(0, deckY + 1 - origin.getY(), 0), Blocks.BELL.defaultBlockState());

        // These two facilities are the same physical sites consumed by the
        // existing Miner/Lumberjack bootstrap and infinite economy tests.
        placeMangroveLumberyard(level, origin.offset(-10, deckY + 1 - origin.getY(), 11), clip);
        BlockPos furnace = origin.offset(9, deckY + 1 - origin.getY(), 11);
        VillageUtilityFeature.placeMine(level, furnace, clip);
        restyleMineHead(level, furnace, clip);
        decoratePiers(level, origin, deckY, clip);

        // This piece is post-processed once for every intersecting chunk. The
        // seed-and-origin-only layout must therefore be recomputed identically
        // on every invocation; mutable world random would tear paths and homes
        // apart at chunk borders.
        for (Residence residence : layout.residences()) {
            connectResidence(level, origin, deckY, residence, clip);
        }
        for (Residence residence : layout.residences()) {
            buildResidence(level, origin, deckY, residence, clip);
        }

        // The south-house threshold is also the most visible central support.
        // Reapply it after the veranda floor so the rooted stilt remains a
        // deliberate part of both the build and the world-generation contract.
        stilt(level, origin.offset(0, deckY - origin.getY(), 8), deckY, clip);
        return true;
    }

    /** Number of seed-selected homes outside the fixed economic core. */
    public static int optionalResidenceCount(long worldSeed, BlockPos origin) {
        return createLayout(worldSeed, origin).residences().size();
    }

    /** Total generated beds, including the four founding beds in the core. */
    public static int expectedBedCount(long worldSeed, BlockPos origin) {
        return CORE_BED_COUNT + createLayout(worldSeed, origin).residences().stream()
                .mapToInt(residence -> residence.style().beds())
                .sum();
    }

    /** Number of distinct seeded roof/gallery treatments in this layout. */
    public static int residenceAppearanceVariantCount(long worldSeed, BlockPos origin) {
        return (int) createLayout(worldSeed, origin).residences().stream()
                .map(Residence::variant)
                .distinct()
                .count();
    }

    /** Compact deterministic identity used to guard seed-dependent variation. */
    public static long layoutSignature(long worldSeed, BlockPos origin) {
        long signature = LAYOUT_SALT;
        for (Residence residence : createLayout(worldSeed, origin).residences()) {
            long value = residence.site().ordinal()
                    | (long) residence.style().ordinal() << 4
                    | (long) residence.variant().ordinal() << 6
                    | (residence.mirrored() ? 1L : 0L) << 8
                    | (long) (residence.lateralOffset() + 2) << 9
                    | (long) (residence.pathBend() + 2) << 12;
            signature = mix64(signature ^ value);
        }
        return signature;
    }

    private static VillageLayout createLayout(long worldSeed, BlockPos origin) {
        RandomSource random = RandomSource.create(mix64(worldSeed ^ origin.asLong() ^ LAYOUT_SALT));
        List<ResidenceSite> available = new ArrayList<>(List.of(ResidenceSite.values()));
        for (int index = available.size() - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            ResidenceSite previous = available.get(index);
            available.set(index, available.get(swap));
            available.set(swap, previous);
        }

        int count = MIN_OPTIONAL_RESIDENCES
                + random.nextInt(MAX_OPTIONAL_RESIDENCES - MIN_OPTIONAL_RESIDENCES + 1);
        List<Residence> residences = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ResidenceStyle style = ResidenceStyle.values()[random.nextInt(ResidenceStyle.values().length)];
            ResidenceVariant variant = ResidenceVariant.values()[random.nextInt(ResidenceVariant.values().length)];
            residences.add(new Residence(available.get(index), style, variant,
                    random.nextInt(5) - 2, random.nextInt(5) - 2, random.nextBoolean()));
        }
        return new VillageLayout(List.copyOf(residences));
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static void plaza(WorldGenLevel level, BlockPos origin, int deckY, BoundingBox clip) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) + Math.abs(z) > 7) {
                    continue;
                }
                BlockPos floor = origin.offset(x, deckY - origin.getY(), z);
                BlockState pattern = (Math.abs(x) == Math.abs(z) || (x == 0 && z == 0))
                        ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                        : Blocks.MANGROVE_PLANKS.defaultBlockState();
                set(level, clip, floor, pattern);
                for (int y = 1; y <= 7; y++) {
                    clear(level, clip, floor.above(y));
                }
            }
        }
        for (int x : new int[]{-3, 3}) {
            for (int z : new int[]{-3, 3}) {
                stilt(level, origin.offset(x, deckY - origin.getY(), z), deckY, clip);
            }
        }
    }

    /** A cultivated straight-trunk Mangrove keeps harvesting atomic and renewable. */
    private static void placeMangroveLumberyard(WorldGenLevel level, BlockPos yard, BoundingBox clip) {
        int floorY = yard.getY() - 1;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) == 4 && Math.abs(z) == 4) {
                    continue;
                }
                BlockPos floor = new BlockPos(yard.getX() + x, floorY, yard.getZ() + z);
                set(level, clip, floor, (x == 0 || z == 0) && ((x + z) & 1) == 0
                        ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                        : Blocks.MANGROVE_PLANKS.defaultBlockState());
                for (int y = 1; y <= 7; y++) {
                    clear(level, clip, floor.above(y));
                }
            }
        }
        for (int x : new int[]{-4, 4}) {
            for (int z : new int[]{-3, 3}) {
                stilt(level, yard.offset(x, -1, z), floorY, clip);
            }
        }

        BlockPos woodcutter = yard.east(2);
        BlockPos treeBase = VillageUtilityFeature.treeBaseFromWoodcutter(woodcutter);
        set(level, clip, treeBase.below(), Blocks.MUD.defaultBlockState());
        for (int y = 0; y < 4; y++) {
            set(level, clip, treeBase.above(y), Blocks.MANGROVE_LOG.defaultBlockState());
        }
        BlockState leaves = Blocks.MANGROVE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockPos canopy = treeBase.above(4);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) + Math.abs(z) <= 3) {
                    set(level, clip, canopy.offset(x, 0, z), leaves);
                }
            }
        }
        set(level, clip, canopy.above(), leaves);

        // An open bamboo-roofed processing shed protects the two real work
        // stations without enclosing the renewable tree or its fibre trellis.
        for (int x : new int[]{0, 4}) {
            for (int z : new int[]{-3, 3}) {
                BlockPos post = yard.offset(x, 0, z);
                for (int y = 0; y <= 3; y++) {
                    set(level, clip, post.above(y), Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState());
                }
            }
        }
        pitchedBambooRoof(level, yard.east(2).below(), floorY, 2, 4, clip);

        // Short stacks of cut timber make this read as a working yard rather
        // than another house. Their horizontal axes are intentional.
        BlockState stackedLog = Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        for (int z = 1; z <= 3; z++) {
            set(level, clip, yard.offset(-3, 0, z), stackedLog);
            if (z >= 2) {
                set(level, clip, yard.offset(-3, 1, z), stackedLog);
            }
        }

        set(level, clip, woodcutter, TotemVillagerBlocks.WOODCUTTER.defaultBlockState());
        clear(level, clip, woodcutter.above());
        BlockPos smithingTable = VillageUtilityFeature.smithingTableFromWoodcutter(woodcutter);
        set(level, clip, smithingTable, Blocks.SMITHING_TABLE.defaultBlockState());
        clear(level, clip, smithingTable.above());

        BlockPos fibreTrellis = woodcutter.north(2);
        for (int y = 0; y <= 2; y++) {
            set(level, clip, fibreTrellis.above(y), Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState());
        }
        BlockState attachedVine = Blocks.VINE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.VineBlock.WEST, true);
        set(level, clip, fibreTrellis.east().above(), attachedVine);
        set(level, clip, fibreTrellis.east().above(2), attachedVine);

        set(level, clip, yard.east(2).above(4), Blocks.IRON_CHAIN.defaultBlockState());
        set(level, clip, yard.east(2).above(3), Blocks.LANTERN.defaultBlockState()
                .setValue(LanternBlock.HANGING, true));
    }

    private static void fishingBasin(WorldGenLevel level, BlockPos origin, int deckY, BoundingBox clip) {
        for (int x = -20; x <= -16; x++) {
            for (int z = 2; z <= 6; z++) {
                set(level, clip, origin.offset(x, deckY - 1 - origin.getY(), z), Blocks.MUD_BRICKS.defaultBlockState());
                set(level, clip, origin.offset(x, deckY - origin.getY(), z), Blocks.WATER.defaultBlockState());
                clear(level, clip, origin.offset(x, deckY + 1 - origin.getY(), z));
            }
        }
        // A solid same-height rim supplies valid, ordinary shoreline casting
        // positions while keeping villagers out of the water.
        boardwalk(level, origin, -22, -14, 1, 1, deckY, clip);
        boardwalk(level, origin, -22, -14, 7, 7, deckY, clip);
        boardwalk(level, origin, -22, -21, 1, 7, deckY, clip);
        boardwalk(level, origin, -15, -14, 1, 7, deckY, clip);
        for (int x : new int[]{-22, -14}) {
            for (int z : new int[]{1, 7}) {
                stilt(level, origin.offset(x, deckY - origin.getY(), z), deckY, clip);
            }
        }

        // Sparse bamboo rails retain multiple ordinary casting positions.
        for (int x : new int[]{-20, -18, -16}) {
            railPost(level, origin.offset(x, deckY - origin.getY(), 1), clip);
        }
        for (int z : new int[]{3, 5}) {
            railPost(level, origin.offset(-22, deckY - origin.getY(), z), clip);
            railPost(level, origin.offset(-14, deckY - origin.getY(), z), clip);
        }
        dockLamp(level, origin.offset(-22, deckY - origin.getY(), 7), clip);
    }

    private static void connectResidence(WorldGenLevel level, BlockPos origin, int deckY,
                                         Residence residence, BoundingBox clip) {
        ResidenceSite site = residence.site();
        BlockPos center = residenceCenter(origin, deckY, residence);
        BlockPos target = center.relative(site.entrance(), residence.style().halfDepth() + 2);
        int targetX = target.getX() - origin.getX();
        int targetZ = target.getZ() - origin.getZ();
        supportedBoardwalkPath(level, origin, deckY,
                site.junctionX(), site.junctionZ(), targetX, targetZ,
                site.pathOrder(), residence.pathBend(), clip);
    }

    private static void supportedBoardwalkPath(WorldGenLevel level, BlockPos origin, int deckY,
                                               int sourceX, int sourceZ, int targetX, int targetZ,
                                               PathOrder order, int bend, BoundingBox clip) {
        boolean splitHorizontal = Math.abs(targetX - sourceX) > Math.abs(targetZ - sourceZ)
                || (Math.abs(targetX - sourceX) == Math.abs(targetZ - sourceZ)
                && order == PathOrder.X_THEN_Z);
        int firstTurnX;
        int firstTurnZ;
        int secondTurnX;
        int secondTurnZ;
        if (order == PathOrder.VIA_SOUTH) {
            int bypassZ = 17 + Math.max(0, bend);
            supportedVerticalBoardwalk(level, origin, deckY, sourceZ, bypassZ, sourceX, clip);
            supportedHorizontalBoardwalk(level, origin, deckY, sourceX, targetX, bypassZ, clip);
            supportedVerticalBoardwalk(level, origin, deckY, bypassZ, targetZ, targetX, clip);
            firstTurnX = sourceX;
            firstTurnZ = bypassZ;
            secondTurnX = targetX;
            secondTurnZ = bypassZ;
        } else if (splitHorizontal) {
            int middleX = pathMidpoint(sourceX, targetX, bend);
            supportedHorizontalBoardwalk(level, origin, deckY, sourceX, middleX, sourceZ, clip);
            supportedVerticalBoardwalk(level, origin, deckY, sourceZ, targetZ, middleX, clip);
            supportedHorizontalBoardwalk(level, origin, deckY, middleX, targetX, targetZ, clip);
            firstTurnX = middleX;
            firstTurnZ = sourceZ;
            secondTurnX = middleX;
            secondTurnZ = targetZ;
        } else {
            int middleZ = pathMidpoint(sourceZ, targetZ, bend);
            supportedVerticalBoardwalk(level, origin, deckY, sourceZ, middleZ, sourceX, clip);
            supportedHorizontalBoardwalk(level, origin, deckY, sourceX, targetX, middleZ, clip);
            supportedVerticalBoardwalk(level, origin, deckY, middleZ, targetZ, targetX, clip);
            firstTurnX = sourceX;
            firstTurnZ = middleZ;
            secondTurnX = targetX;
            secondTurnZ = middleZ;
        }
        supportedPathTurn(level, origin, deckY, firstTurnX, firstTurnZ, clip);
        supportedPathTurn(level, origin, deckY, secondTurnX, secondTurnZ, clip);
        stilt(level, origin.offset(sourceX, deckY - origin.getY(), sourceZ), deckY, clip);
        stilt(level, origin.offset(targetX, deckY - origin.getY(), targetZ), deckY, clip);
    }

    private static int pathMidpoint(int source, int target, int bend) {
        int minimum = Math.min(source, target);
        int maximum = Math.max(source, target);
        int midpoint = Math.floorDiv(source + target, 2) + bend;
        if (maximum - minimum <= 2) {
            return Math.floorDiv(source + target, 2);
        }
        return Math.max(minimum + 1, Math.min(maximum - 1, midpoint));
    }

    private static void supportedPathTurn(WorldGenLevel level, BlockPos origin, int deckY,
                                          int x, int z, BoundingBox clip) {
        BlockPos turn = origin.offset(x, deckY - origin.getY(), z);
        stilt(level, turn, deckY, clip);
        set(level, clip, turn, Blocks.BAMBOO_MOSAIC.defaultBlockState());
    }

    private static void supportedHorizontalBoardwalk(WorldGenLevel level, BlockPos origin, int deckY,
                                                     int firstX, int lastX, int z, BoundingBox clip) {
        int minimum = Math.min(firstX, lastX);
        int maximum = Math.max(firstX, lastX);
        boardwalk(level, origin, minimum, maximum, z - 1, z + 1, deckY, clip);
        for (int x = minimum; x <= maximum; x += 4) {
            stilt(level, origin.offset(x, deckY - origin.getY(), z), deckY, clip);
        }
        stilt(level, origin.offset(maximum, deckY - origin.getY(), z), deckY, clip);
    }

    private static void supportedVerticalBoardwalk(WorldGenLevel level, BlockPos origin, int deckY,
                                                   int firstZ, int lastZ, int x, BoundingBox clip) {
        int minimum = Math.min(firstZ, lastZ);
        int maximum = Math.max(firstZ, lastZ);
        boardwalk(level, origin, x - 1, x + 1, minimum, maximum, deckY, clip);
        for (int z = minimum; z <= maximum; z += 4) {
            stilt(level, origin.offset(x, deckY - origin.getY(), z), deckY, clip);
        }
        stilt(level, origin.offset(x, deckY - origin.getY(), maximum), deckY, clip);
    }

    private static void buildResidence(WorldGenLevel level, BlockPos origin, int deckY,
                                       Residence residence, BoundingBox clip) {
        ResidenceSite site = residence.site();
        ResidenceStyle style = residence.style();
        ResidenceVariant variant = residence.variant();
        BlockPos center = residenceCenter(origin, origin.getY(), residence);
        Direction entrance = site.entrance();
        int halfWidth = style.halfWidth();
        int halfDepth = style.halfDepth();
        int roofWidth = style.roofWidth() + variant.roofWidthDelta();
        int roofLength = style.roofLength() + variant.roofLengthDelta();

        for (int side = -roofWidth - 2; side <= roofWidth + 2; side++) {
            for (int depth = -roofLength - 1; depth <= roofLength + 1; depth++) {
                for (int y = 1; y <= 10; y++) {
                    clear(level, clip, local(center, entrance, side, depth, deckY + y));
                }
            }
        }

        for (int side = -halfWidth; side <= halfWidth; side++) {
            for (int depth = -halfDepth; depth <= halfDepth; depth++) {
                BlockState floor = (side == 0 || Math.abs(depth) == halfDepth) && ((side + depth) & 1) == 0
                        ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                        : Blocks.MANGROVE_PLANKS.defaultBlockState();
                set(level, clip, local(center, entrance, side, depth, deckY), floor);
            }
        }

        for (int side : new int[]{-halfWidth, halfWidth}) {
            for (int depth : new int[]{-halfDepth, halfDepth}) {
                BlockPos corner = local(center, entrance, side, depth, deckY);
                stilt(level, corner, deckY, clip);
                for (int y = 1; y <= 4; y++) {
                    set(level, clip, corner.above(y), Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState());
                }
            }
        }

        for (int side = -halfWidth; side <= halfWidth; side++) {
            for (int depth = -halfDepth; depth <= halfDepth; depth++) {
                if (Math.abs(side) != halfWidth && Math.abs(depth) != halfDepth) {
                    continue;
                }
                boolean doorway = depth == halfDepth && side == 0;
                boolean corner = Math.abs(side) == halfWidth && Math.abs(depth) == halfDepth;
                for (int y = 1; y <= 3; y++) {
                    BlockPos wall = local(center, entrance, side, depth, deckY + y);
                    if (doorway && y <= 2) {
                        clear(level, clip, wall);
                        continue;
                    }
                    boolean window = y == 2 && !corner
                            && ((Math.abs(side) == halfWidth && Math.abs(depth) <= 1)
                            || (Math.abs(depth) == halfDepth && Math.abs(side) == halfWidth - 1));
                    set(level, clip, wall, corner
                            ? Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                            : window ? Blocks.BAMBOO_FENCE.defaultBlockState()
                            : residenceWall(variant, side, depth, y));
                }
            }
        }

        int porchHalfWidth = variant == ResidenceVariant.DEEP_EAVES
                ? halfWidth : Math.min(2, halfWidth);
        for (int depth = halfDepth + 1; depth <= halfDepth + 2; depth++) {
            for (int side = -porchHalfWidth; side <= porchHalfWidth; side++) {
                set(level, clip, local(center, entrance, side, depth, deckY),
                        side == 0 ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                                : Blocks.MANGROVE_PLANKS.defaultBlockState());
            }
        }
        for (int side : new int[]{-porchHalfWidth, porchHalfWidth}) {
            BlockPos outerPorch = local(center, entrance, side, halfDepth + 2, deckY);
            stilt(level, outerPorch, deckY, clip);
            railPost(level, outerPorch, clip);
        }

        if (style.rearVeranda()) {
            for (int depth = -halfDepth - 2; depth <= -halfDepth - 1; depth++) {
                for (int side = -2; side <= 2; side++) {
                    set(level, clip, local(center, entrance, side, depth, deckY),
                            Blocks.MANGROVE_PLANKS.defaultBlockState());
                }
            }
            for (int side : new int[]{-2, 2}) {
                BlockPos rearCorner = local(center, entrance, side, -halfDepth - 2, deckY);
                stilt(level, rearCorner, deckY, clip);
                railPost(level, rearCorner, clip);
            }
        }

        pitchedBambooRoof(level, center, deckY, roofWidth, roofLength,
                entrance.getAxis(), clip);
        decorateResidenceVariant(level, center, deckY, residence, clip);

        Direction bedFacing = entrance.getOpposite();
        for (int bedIndex = 0; bedIndex < style.beds(); bedIndex++) {
            int side = style.beds() == 1 ? 0 : bedIndex == 0 ? -1 : 1;
            BlockState bed = ((bedIndex + (residence.mirrored() ? 1 : 0)) & 1) == 0
                    ? Blocks.BED.cyan().defaultBlockState()
                    : Blocks.BED.lightBlue().defaultBlockState();
            placeBed(level, local(center, entrance, side, 0, deckY + 1), bedFacing, bed, clip);
        }

        int furnishingSide = residence.mirrored() ? halfWidth - 1 : 1 - halfWidth;
        int furnishingDepth = 1 - halfDepth;
        set(level, clip, local(center, entrance, furnishingSide, furnishingDepth, deckY + 1),
                Blocks.CRAFTING_TABLE.defaultBlockState());
        if (style.beds() > 1) {
            set(level, clip, local(center, entrance, -furnishingSide, furnishingDepth, deckY + 1),
                    Blocks.CHEST.defaultBlockState());
        }
        set(level, clip, local(center, entrance, 0, 0, deckY + 4), Blocks.IRON_CHAIN.defaultBlockState());
        set(level, clip, local(center, entrance, 0, 0, deckY + 3),
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private static BlockPos residenceCenter(BlockPos origin, int y, Residence residence) {
        ResidenceSite site = residence.site();
        return origin.offset(site.centerX(), y - origin.getY(), site.centerZ())
                .relative(site.entrance().getClockWise(), residence.lateralOffset());
    }

    private static BlockState residenceWall(ResidenceVariant variant, int side, int depth, int y) {
        if (variant == ResidenceVariant.HIGH_GABLE && y == 1 && ((side + depth) & 1) == 0) {
            return Blocks.PACKED_MUD.defaultBlockState();
        }
        if (variant == ResidenceVariant.DEEP_EAVES && y == 3 && ((side + depth) & 1) == 0) {
            return Blocks.BAMBOO_MOSAIC.defaultBlockState();
        }
        return Blocks.BAMBOO_PLANKS.defaultBlockState();
    }

    private static void decorateResidenceVariant(WorldGenLevel level, BlockPos center, int deckY,
                                                  Residence residence, BoundingBox clip) {
        if (residence.variant() != ResidenceVariant.SIDE_GALLERY) {
            return;
        }
        ResidenceStyle style = residence.style();
        Direction entrance = residence.site().entrance();
        int direction = residence.mirrored() ? 1 : -1;
        int innerSide = direction * (style.halfWidth() + 1);
        int outerSide = direction * (style.halfWidth() + 2);
        int minimumDepth = 1 - style.halfDepth();
        int maximumDepth = style.halfDepth() - 1;
        for (int depth = minimumDepth; depth <= maximumDepth; depth++) {
            set(level, clip, local(center, entrance, innerSide, depth, deckY),
                    Blocks.MANGROVE_PLANKS.defaultBlockState());
            set(level, clip, local(center, entrance, outerSide, depth, deckY),
                    ((depth - minimumDepth) & 1) == 0
                            ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                            : Blocks.MANGROVE_PLANKS.defaultBlockState());
            set(level, clip, local(center, entrance, innerSide, depth, deckY + 4),
                    Blocks.BAMBOO_MOSAIC_SLAB.defaultBlockState());
            set(level, clip, local(center, entrance, outerSide, depth, deckY + 4),
                    Blocks.BAMBOO_MOSAIC_SLAB.defaultBlockState());
        }
        for (int depth : new int[]{minimumDepth, maximumDepth}) {
            BlockPos outerCorner = local(center, entrance, outerSide, depth, deckY);
            stilt(level, outerCorner, deckY, clip);
            set(level, clip, outerCorner, Blocks.BAMBOO_MOSAIC.defaultBlockState());
            for (int y = 1; y <= 3; y++) {
                set(level, clip, outerCorner.above(y), Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState());
            }
        }
        BlockPos galleryLamp = local(center, entrance, outerSide, 0, deckY);
        dockLamp(level, galleryLamp, clip);
    }

    private static BlockPos local(BlockPos center, Direction entrance, int side, int depth, int y) {
        return center.relative(entrance.getClockWise(), side).relative(entrance, depth).atY(y);
    }

    private static void buildLonghouse(WorldGenLevel level, BlockPos center, int deckY,
                                       Direction entrance, boolean broadPorch, BoundingBox clip) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 1; y <= 9; y++) {
                    clear(level, clip, new BlockPos(center.getX() + x, deckY + y, center.getZ() + z));
                }
            }
        }

        for (int x = -3; x <= 3; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockState floor = (x == 0 || Math.abs(z) == 4) && ((x + z) & 1) == 0
                        ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                        : Blocks.MANGROVE_PLANKS.defaultBlockState();
                set(level, clip, new BlockPos(center.getX() + x, deckY, center.getZ() + z), floor);
            }
        }

        for (int x : new int[]{-3, 3}) {
            for (int z : new int[]{-4, 4}) {
                BlockPos corner = new BlockPos(center.getX() + x, deckY, center.getZ() + z);
                stilt(level, corner, deckY, clip);
                for (int y = 1; y <= 4; y++) {
                    set(level, clip, corner.above(y), Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState());
                }
            }
        }

        for (int x = -3; x <= 3; x++) {
            for (int z = -4; z <= 4; z++) {
                if (Math.abs(x) != 3 && Math.abs(z) != 4) {
                    continue;
                }
                if (Math.abs(x) == 3 && Math.abs(z) == 4) {
                    continue;
                }
                // Both central longhouses sit directly across the north-south
                // housing spine. Keep a true breezeway through each one so
                // the rear residence branches are reachable, not merely
                // attached to floor blocks hidden behind a solid wall.
                boolean doorway = isOpening(x, z, entrance)
                        || isOpening(x, z, entrance.getOpposite());
                boolean sideBreezeway = broadPorch && Math.abs(x) == 3 && z == -2;
                for (int y = 1; y <= 3; y++) {
                    if ((doorway || sideBreezeway) && y <= 2) {
                        clear(level, clip, new BlockPos(center.getX() + x, deckY + y, center.getZ() + z));
                        continue;
                    }
                    boolean window = y == 2 && ((Math.abs(x) == 3 && Math.abs(z) <= 1)
                            || (Math.abs(z) == 4 && Math.abs(x) == 2));
                    set(level, clip, new BlockPos(center.getX() + x, deckY + y, center.getZ() + z),
                            window ? Blocks.BAMBOO_FENCE.defaultBlockState()
                                    : Blocks.BAMBOO_PLANKS.defaultBlockState());
                }
            }
        }

        // A two-deep covered front threshold connects directly to the central
        // pier; the opposite doorway preserves the through-house spine.
        for (int distance = 5; distance <= 6; distance++) {
            for (int side = -2; side <= 2; side++) {
                BlockPos porch = center.relative(entrance, distance)
                        .relative(entrance.getClockWise(), side).atY(deckY);
                set(level, clip, porch, side == 0
                        ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                        : Blocks.MANGROVE_PLANKS.defaultBlockState());
            }
        }
        for (int distance = 5; distance <= 6; distance++) {
            for (int side : new int[]{-2, 2}) {
                railPost(level, center.relative(entrance, distance)
                        .relative(entrance.getClockWise(), side).atY(deckY), clip);
            }
        }

        if (broadPorch) {
            Direction rear = entrance.getOpposite();
            for (int distance = 5; distance <= 6; distance++) {
                for (int side = -3; side <= 3; side++) {
                    BlockPos porch = center.relative(rear, distance)
                            .relative(rear.getClockWise(), side).atY(deckY);
                    set(level, clip, porch, Blocks.MANGROVE_PLANKS.defaultBlockState());
                }
            }
            for (int side : new int[]{-3, 3}) {
                dockLamp(level, center.relative(rear, 6)
                        .relative(rear.getClockWise(), side).atY(deckY), clip);
            }
        }

        pitchedBambooRoof(level, center, deckY, 4, 5, clip);
        set(level, clip, center.above(deckY + 6 - center.getY()), Blocks.IRON_CHAIN.defaultBlockState());
        set(level, clip, center.above(deckY + 5 - center.getY()), Blocks.LANTERN.defaultBlockState()
                .setValue(LanternBlock.HANGING, true));
    }

    private static boolean isOpening(int x, int z, Direction direction) {
        return switch (direction) {
            case NORTH -> z == -4 && x == 0;
            case SOUTH -> z == 4 && x == 0;
            case WEST -> x == -3 && z == 0;
            case EAST -> x == 3 && z == 0;
            default -> false;
        };
    }

    private static void buildFishingHouse(WorldGenLevel level, BlockPos center, int deckY, BoundingBox clip) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 6; z++) {
                for (int y = 1; y <= 9; y++) {
                    clear(level, clip, new BlockPos(center.getX() + x, deckY + y, center.getZ() + z));
                }
            }
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -4; z <= 5; z++) {
                BlockPos floor = new BlockPos(center.getX() + x, deckY, center.getZ() + z);
                set(level, clip, floor, (z == 0 || (x == 0 && z >= 2))
                        ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                        : Blocks.MANGROVE_PLANKS.defaultBlockState());
            }
        }
        for (int x : new int[]{-3, 3}) {
            for (int z : new int[]{-4, 2, 5}) {
                stilt(level, new BlockPos(center.getX() + x, deckY, center.getZ() + z), deckY, clip);
            }
        }

        // The smokehouse has a solid weather wall to the north, windowed side
        // screens, and no front wall between the Barrel and the fish-drying deck.
        for (int x = -3; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                set(level, clip, new BlockPos(center.getX() + x, deckY + y, center.getZ() - 4),
                        Math.abs(x) == 3 ? Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                                : Blocks.BAMBOO_PLANKS.defaultBlockState());
            }
        }
        for (int x : new int[]{-3, 3}) {
            for (int z = -3; z <= 1; z++) {
                for (int y = 1; y <= 3; y++) {
                    boolean window = y == 2 && (z == -2 || z == 0);
                    set(level, clip, new BlockPos(center.getX() + x, deckY + y, center.getZ() + z),
                            window ? Blocks.BAMBOO_FENCE.defaultBlockState()
                                    : Blocks.BAMBOO_PLANKS.defaultBlockState());
                }
            }
        }
        for (int x : new int[]{-3, 3}) {
            BlockPos frontPost = new BlockPos(center.getX() + x, deckY, center.getZ() + 2);
            for (int y = 1; y <= 4; y++) {
                set(level, clip, frontPost.above(y), Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState());
            }
        }
        pitchedBambooRoof(level, center.north(), deckY, 4, 4, clip);

        // An uncovered drying rack frames the Campfire at relative z=4 while
        // keeping its smoke column and the villager's route completely open.
        BlockState dryingBeam = Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        for (int x : new int[]{-2, 2}) {
            for (int z : new int[]{3, 5}) {
                BlockPos post = new BlockPos(center.getX() + x, deckY, center.getZ() + z);
                for (int y = 1; y <= 3; y++) {
                    set(level, clip, post.above(y), Blocks.BAMBOO_FENCE.defaultBlockState());
                }
            }
            for (int z = 3; z <= 5; z++) {
                set(level, clip, new BlockPos(center.getX() + x, deckY + 3, center.getZ() + z), dryingBeam);
            }
            set(level, clip, new BlockPos(center.getX() + x, deckY + 2, center.getZ() + 4),
                    Blocks.IRON_CHAIN.defaultBlockState());
        }
        set(level, clip, new BlockPos(center.getX(), deckY + 6, center.getZ() - 1),
                Blocks.IRON_CHAIN.defaultBlockState());
        set(level, clip, new BlockPos(center.getX(), deckY + 5, center.getZ() - 1),
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    /** Builds the tall, narrow bamboo roof shared by homes and working sheds. */
    private static void pitchedBambooRoof(WorldGenLevel level, BlockPos center, int deckY,
                                          int halfWidth, int halfLength, BoundingBox clip) {
        pitchedBambooRoof(level, center, deckY, halfWidth, halfLength, Direction.Axis.Z, clip);
    }

    /** Builds the same inward-rising roof along either horizontal axis. */
    private static void pitchedBambooRoof(WorldGenLevel level, BlockPos center, int deckY,
                                          int halfWidth, int halfLength, Direction.Axis ridgeAxis,
                                          BoundingBox clip) {
        BlockState eaveBeam = Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, ridgeAxis);
        Direction negativeSlope = ridgeAxis == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
        Direction positiveSlope = ridgeAxis == Direction.Axis.Z ? Direction.WEST : Direction.NORTH;
        for (int along = -halfLength; along <= halfLength; along++) {
            set(level, clip, roofPosition(center, ridgeAxis, -halfWidth, along, deckY + 3), eaveBeam);
            set(level, clip, roofPosition(center, ridgeAxis, halfWidth, along, deckY + 3), eaveBeam);
        }
        for (int layer = 0; layer < halfWidth; layer++) {
            int sideOffset = halfWidth - layer;
            int roofY = deckY + 4 + layer;
            for (int along = -halfLength; along <= halfLength; along++) {
                set(level, clip, roofPosition(center, ridgeAxis, -sideOffset, along, roofY),
                        Blocks.BAMBOO_MOSAIC_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, negativeSlope));
                set(level, clip, roofPosition(center, ridgeAxis, sideOffset, along, roofY),
                        Blocks.BAMBOO_MOSAIC_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, positiveSlope));
            }
        }
        int ridgeY = deckY + 4 + halfWidth;
        for (int along = -halfLength; along <= halfLength; along++) {
            set(level, clip, roofPosition(center, ridgeAxis, 0, along, ridgeY),
                    Blocks.MANGROVE_SLAB.defaultBlockState());
        }

        // Recessed gables expose the stair edges and make the roof silhouette
        // readable from both the central plaza and the surrounding swamp.
        for (int along : new int[]{-halfLength + 1, halfLength - 1}) {
            for (int layer = 0; layer < halfWidth; layer++) {
                int roofY = deckY + 4 + layer;
                int innerWidth = halfWidth - layer - 1;
                for (int side = -innerWidth; side <= innerWidth; side++) {
                    set(level, clip, roofPosition(center, ridgeAxis, side, along, roofY),
                            Blocks.BAMBOO_PLANKS.defaultBlockState());
                }
            }
        }
        for (int along : new int[]{-halfLength - 1, halfLength + 1}) {
            set(level, clip, roofPosition(center, ridgeAxis, 0, along, ridgeY),
                    Blocks.MANGROVE_FENCE.defaultBlockState());
        }
    }

    private static BlockPos roofPosition(BlockPos center, Direction.Axis ridgeAxis,
                                         int side, int along, int y) {
        return ridgeAxis == Direction.Axis.Z
                ? new BlockPos(center.getX() + side, y, center.getZ() + along)
                : new BlockPos(center.getX() + along, y, center.getZ() + side);
    }

    private static void buildBellPavilion(WorldGenLevel level, BlockPos origin, int deckY, BoundingBox clip) {
        BlockState verticalPost = Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int x : new int[]{-2, 2}) {
            for (int z : new int[]{-2, 2}) {
                BlockPos post = origin.offset(x, deckY - origin.getY(), z);
                stilt(level, post, deckY, clip);
                for (int y = 1; y <= 4; y++) {
                    set(level, clip, post.above(y), verticalPost);
                }
            }
        }

        BlockState xBeam = verticalPost.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState zBeam = verticalPost.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        for (int x = -1; x <= 1; x++) {
            set(level, clip, origin.offset(x, deckY + 4 - origin.getY(), -2), xBeam);
            set(level, clip, origin.offset(x, deckY + 4 - origin.getY(), 2), xBeam);
        }
        for (int z = -1; z <= 1; z++) {
            set(level, clip, origin.offset(-2, deckY + 4 - origin.getY(), z), zBeam);
            set(level, clip, origin.offset(2, deckY + 4 - origin.getY(), z), zBeam);
        }

        pavilionRoofTier(level, origin, deckY + 5, 3, clip);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                set(level, clip, origin.offset(x, deckY + 5 - origin.getY(), z),
                        Blocks.BAMBOO_MOSAIC_SLAB.defaultBlockState());
            }
        }
        pavilionRoofTier(level, origin, deckY + 6, 2, clip);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                set(level, clip, origin.offset(x, deckY + 6 - origin.getY(), z),
                        Blocks.BAMBOO_MOSAIC_SLAB.defaultBlockState());
            }
        }
        set(level, clip, origin.offset(0, deckY + 7 - origin.getY(), 0),
                Blocks.BAMBOO_MOSAIC_SLAB.defaultBlockState());
        for (int x : new int[]{-1, 1}) {
            for (int z : new int[]{-1, 1}) {
                set(level, clip, origin.offset(x, deckY + 3 - origin.getY(), z),
                        Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
        }
    }

    private static void pavilionRoofTier(WorldGenLevel level, BlockPos origin, int y, int radius,
                                         BoundingBox clip) {
        for (int offset = -radius + 1; offset <= radius - 1; offset++) {
            set(level, clip, new BlockPos(origin.getX() + offset, y, origin.getZ() - radius),
                    Blocks.BAMBOO_MOSAIC_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            set(level, clip, new BlockPos(origin.getX() + offset, y, origin.getZ() + radius),
                    Blocks.BAMBOO_MOSAIC_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
            set(level, clip, new BlockPos(origin.getX() - radius, y, origin.getZ() + offset),
                    Blocks.BAMBOO_MOSAIC_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
            set(level, clip, new BlockPos(origin.getX() + radius, y, origin.getZ() + offset),
                    Blocks.BAMBOO_MOSAIC_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
        }
        for (int x : new int[]{-radius, radius}) {
            for (int z : new int[]{-radius, radius}) {
                set(level, clip, origin.offset(x, y - origin.getY(), z), Blocks.BAMBOO_MOSAIC.defaultBlockState());
            }
        }
    }

    private static void restyleMineHead(WorldGenLevel level, BlockPos furnace, BoundingBox clip) {
        for (int x = -1; x <= 7; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -4; y <= 5; y++) {
                    BlockPos position = furnace.offset(x, y, z);
                    if (!clip.isInside(position) || !level.ensureCanWrite(position)) {
                        continue;
                    }
                    BlockState existing = level.getBlockState(position);
                    boolean retainingWall = y < 0 && existing.is(Blocks.STONE)
                            && (x == -1 || x == 7 || z == -4 || z == 4);
                    BlockState replacement = retainingWall
                            ? ((x + y + z) & 3) == 0
                            ? Blocks.PACKED_MUD.defaultBlockState()
                            : Blocks.MUD_BRICKS.defaultBlockState()
                            : mangroveMinePalette(existing);
                    if (!replacement.equals(existing)) {
                        level.setBlock(position, replacement, 2);
                    }
                }
            }
        }
        // The mapped utility shelter supplies the functional posts and rim;
        // this steep cap gives the Mangrove variant its own village identity.
        pitchedBambooRoof(level, VillageUtilityFeature.mineCenter(furnace), furnace.getY(), 4, 4, clip);
    }

    private static BlockState mangroveMinePalette(BlockState existing) {
        if (existing.is(Blocks.COBBLESTONE)) {
            return Blocks.MUD_BRICKS.defaultBlockState();
        }
        if (existing.is(Blocks.MOSSY_COBBLESTONE)) {
            return Blocks.PACKED_MUD.defaultBlockState();
        }
        if (existing.is(Blocks.STRIPPED_OAK_LOG)) {
            return Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                    .setValue(RotatedPillarBlock.AXIS, existing.getValue(RotatedPillarBlock.AXIS));
        }
        if (existing.is(Blocks.OAK_FENCE)) {
            BlockState fence = Blocks.BAMBOO_FENCE.defaultBlockState();
            fence = fence.setValue(BlockStateProperties.NORTH, existing.getValue(BlockStateProperties.NORTH));
            fence = fence.setValue(BlockStateProperties.EAST, existing.getValue(BlockStateProperties.EAST));
            fence = fence.setValue(BlockStateProperties.SOUTH, existing.getValue(BlockStateProperties.SOUTH));
            return fence.setValue(BlockStateProperties.WEST, existing.getValue(BlockStateProperties.WEST));
        }
        if (existing.is(Blocks.OAK_FENCE_GATE)) {
            return Blocks.BAMBOO_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.FACING, existing.getValue(FenceGateBlock.FACING))
                    .setValue(BlockStateProperties.OPEN, existing.getValue(BlockStateProperties.OPEN));
        }
        if (existing.is(Blocks.SPRUCE_STAIRS)) {
            return Blocks.BAMBOO_MOSAIC_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, existing.getValue(StairBlock.FACING).getOpposite());
        }
        if (existing.is(Blocks.SPRUCE_SLAB)) {
            return Blocks.BAMBOO_MOSAIC_SLAB.defaultBlockState();
        }
        return existing;
    }

    private static void decoratePiers(WorldGenLevel level, BlockPos origin, int deckY, BoundingBox clip) {
        for (int z : new int[]{-7, -6, 6, 7}) {
            railPost(level, origin.offset(-2, deckY - origin.getY(), z), clip);
            railPost(level, origin.offset(2, deckY - origin.getY(), z), clip);
        }
        for (int x : new int[]{-7, -6, 6, 7}) {
            railPost(level, origin.offset(x, deckY - origin.getY(), -2), clip);
            railPost(level, origin.offset(x, deckY - origin.getY(), 2), clip);
        }
        for (int[] coordinate : new int[][]{
                {-18, -2}, {-14, -2}, {-5, -2}, {5, 2},
                {-14, 9}, {-14, 15}, {7, 9}, {7, 15},
                {0, -19}
        }) {
            BlockPos base = origin.offset(coordinate[0], deckY - origin.getY(), coordinate[1]);
            set(level, clip, base, Blocks.BAMBOO_MOSAIC.defaultBlockState());
            dockLamp(level, base, clip);
        }
    }

    private record VillageLayout(List<Residence> residences) {
    }

    private record Residence(ResidenceSite site, ResidenceStyle style, ResidenceVariant variant,
                             int lateralOffset, int pathBend, boolean mirrored) {
    }

    private enum ResidenceVariant {
        HIGH_GABLE(1, 0),
        DEEP_EAVES(0, 1),
        SIDE_GALLERY(0, 0);

        private final int roofWidthDelta;
        private final int roofLengthDelta;

        ResidenceVariant(int roofWidthDelta, int roofLengthDelta) {
            this.roofWidthDelta = roofWidthDelta;
            this.roofLengthDelta = roofLengthDelta;
        }

        private int roofWidthDelta() {
            return roofWidthDelta;
        }

        private int roofLengthDelta() {
            return roofLengthDelta;
        }
    }

    private enum ResidenceStyle {
        COTTAGE(2, 3, 3, 4, 1, false),
        FAMILY_HOUSE(3, 3, 4, 4, 2, false),
        LONGHOUSE(3, 4, 4, 5, 2, true);

        private final int halfWidth;
        private final int halfDepth;
        private final int roofWidth;
        private final int roofLength;
        private final int beds;
        private final boolean rearVeranda;

        ResidenceStyle(int halfWidth, int halfDepth, int roofWidth, int roofLength,
                       int beds, boolean rearVeranda) {
            this.halfWidth = halfWidth;
            this.halfDepth = halfDepth;
            this.roofWidth = roofWidth;
            this.roofLength = roofLength;
            this.beds = beds;
            this.rearVeranda = rearVeranda;
        }

        private int halfWidth() {
            return halfWidth;
        }

        private int halfDepth() {
            return halfDepth;
        }

        private int roofWidth() {
            return roofWidth;
        }

        private int roofLength() {
            return roofLength;
        }

        private int beds() {
            return beds;
        }

        private boolean rearVeranda() {
            return rearVeranda;
        }
    }

    private enum ResidenceSite {
        NORTH_WEST(-13, -27, Direction.SOUTH, 0, -18, PathOrder.X_THEN_Z),
        NORTH_EAST(13, -27, Direction.SOUTH, 0, -18, PathOrder.X_THEN_Z),
        WEST_NORTH(-28, -9, Direction.EAST, -21, 0, PathOrder.X_THEN_Z),
        EAST_NORTH(28, -8, Direction.WEST, 16, 0, PathOrder.X_THEN_Z),
        WEST_SOUTH(-28, 14, Direction.EAST, -21, 7, PathOrder.X_THEN_Z),
        // Branch before the Mine and pass beyond its southern eaves. The
        // direct former junction at (16, 11) was inside the fenced Mine head.
        EAST_SOUTH(28, 14, Direction.WEST, 7, 11, PathOrder.VIA_SOUTH),
        SOUTH_WEST(-14, 29, Direction.NORTH, 0, 19, PathOrder.Z_THEN_X),
        SOUTH_EAST(14, 29, Direction.NORTH, 0, 19, PathOrder.Z_THEN_X);

        private final int centerX;
        private final int centerZ;
        private final Direction entrance;
        private final int junctionX;
        private final int junctionZ;
        private final PathOrder pathOrder;

        ResidenceSite(int centerX, int centerZ, Direction entrance,
                      int junctionX, int junctionZ, PathOrder pathOrder) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.entrance = entrance;
            this.junctionX = junctionX;
            this.junctionZ = junctionZ;
            this.pathOrder = pathOrder;
        }

        private int centerX() {
            return centerX;
        }

        private int centerZ() {
            return centerZ;
        }

        private Direction entrance() {
            return entrance;
        }

        private int junctionX() {
            return junctionX;
        }

        private int junctionZ() {
            return junctionZ;
        }

        private PathOrder pathOrder() {
            return pathOrder;
        }
    }

    private enum PathOrder {
        X_THEN_Z,
        Z_THEN_X,
        VIA_SOUTH
    }

    private static void placeBed(WorldGenLevel level, BlockPos foot, Direction facing,
                                 BlockState bed, BoundingBox clip) {
        set(level, clip, foot, bed.setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT));
        set(level, clip, foot.relative(facing),
                bed.setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.HEAD));
    }

    private static void boardwalk(WorldGenLevel level, BlockPos origin, int minimumX, int maximumX,
                                  int minimumZ, int maximumZ, int deckY, BoundingBox clip) {
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                BlockPos deck = origin.offset(x, deckY - origin.getY(), z);
                set(level, clip, deck, ((x + z) & 3) == 0
                        ? Blocks.BAMBOO_MOSAIC.defaultBlockState()
                        : Blocks.MANGROVE_PLANKS.defaultBlockState());
                clear(level, clip, deck.above());
                clear(level, clip, deck.above(2));
                clear(level, clip, deck.above(3));
            }
        }
    }

    private static void railPost(WorldGenLevel level, BlockPos base, BoundingBox clip) {
        set(level, clip, base.above(), Blocks.BAMBOO_FENCE.defaultBlockState());
    }

    private static void dockLamp(WorldGenLevel level, BlockPos base, BoundingBox clip) {
        set(level, clip, base.above(), Blocks.BAMBOO_FENCE.defaultBlockState());
        set(level, clip, base.above(2), Blocks.BAMBOO_FENCE.defaultBlockState());
        set(level, clip, base.above(3), Blocks.LANTERN.defaultBlockState());
    }

    private static void stilt(WorldGenLevel level, BlockPos top, int deckY, BoundingBox clip) {
        // A multi-chunk structure piece is post-processed once per intersecting
        // chunk. Do not inspect terrain below a column owned by another chunk:
        // doing so is an unsafe cross-chunk read while FEATURES are generating.
        if (!clip.isInside(top)) {
            return;
        }
        int bottom = deckY - SUPPORT_DEPTH;
        for (int y = deckY - 1; y >= deckY - SUPPORT_DEPTH; y--) {
            BlockPos candidate = new BlockPos(top.getX(), y, top.getZ());
            BlockState state = level.getBlockState(candidate);
            if (state.getFluidState().isEmpty() && !state.isAir()
                    && state.isFaceSturdy(level, candidate, Direction.UP)) {
                bottom = y + 1;
                break;
            }
        }
        BlockState post = Blocks.STRIPPED_MANGROVE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        for (int y = bottom; y <= deckY; y++) {
            set(level, clip, new BlockPos(top.getX(), y, top.getZ()),
                    y == bottom && bottom < deckY
                            ? Blocks.MUDDY_MANGROVE_ROOTS.defaultBlockState()
                            : post);
        }
    }

    private static void clear(WorldGenLevel level, BoundingBox clip, BlockPos position) {
        set(level, clip, position, Blocks.AIR.defaultBlockState());
    }

    private static void set(WorldGenLevel level, BoundingBox clip, BlockPos position, BlockState state) {
        if (clip.isInside(position) && level.ensureCanWrite(position)) {
            level.setBlock(position, state, 2);
        }
    }
}
