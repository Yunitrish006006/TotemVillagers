package dev.totem.villagers.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.totem.villagers.builder.BuilderSite;
import dev.totem.villagers.builder.BuilderSiteSavedData;
import dev.totem.villagers.builder.VanillaVillageBlueprints;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.runtime.WorldEnablementRuntime;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.npc.villager.Villager;

/** Minimal operator control plane; rollout stays per world and defaults to disabled. */
public final class TotemVillagersCommands {
    private TotemVillagersCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("totemvillagers")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN));
            var modeCommand = Commands.literal("mode");
            modeCommand.then(Commands.argument("mode", StringArgumentType.word())
                            .suggests((context, builder) -> {
                                for (WorkBackedTradingMode configuredMode : WorkBackedTradingMode.values()) {
                                    builder.suggest(configuredMode.id());
                                }
                                return builder.buildFuture();
                            })
                            .executes(context -> setMode(context.getSource(), StringArgumentType.getString(context, "mode"))));
            modeCommand.then(Commands.literal("status").executes(context -> showMode(context.getSource())));
            root.then(modeCommand);
            root.then(Commands.literal("start").executes(context -> startPlaying(context.getSource())));
            root.then(Commands.literal("role")
                    .then(Commands.argument("villager", UuidArgument.uuid())
                            .then(Commands.argument("role", StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        builder.suggest("totem:miner");
                                        builder.suggest("totem:lumberjack");
                                        builder.suggest("totem:builder");
                                        builder.suggest("totem:guard");
                                        return builder.buildFuture();
                                    })
                                    .executes(context -> assignSpecialistRole(context.getSource(),
                                            UuidArgument.getUuid(context, "villager"),
                                            StringArgumentType.getString(context, "role"))))));
            root.then(workZoneCommands());
            root.then(builderSiteCommands());
            root.then(needsCommands());
            root.then(guardPostCommands());
            dispatcher.register(root);
        });
    }

    private static int setMode(net.minecraft.commands.CommandSourceStack source, String value) {
        WorkBackedTradingMode mode;
        try {
            mode = WorkBackedTradingMode.fromId(value);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("Unknown Totem Villagers mode: " + value));
            return 0;
        }
        WorkBackedTradingSettingsSavedData.forServer(source.getServer()).setMode(mode);
        WorldEnablementRuntime.ApplyResult result = WorldEnablementRuntime.apply(source.getServer(), mode);
        String detail = switch (mode) {
            case ENFORCED -> "; initialised " + result.initialisedVillagers() + " loaded villagers; starter supplies are granted once";
            case VANILLA_ROLLBACK -> "; restored vanilla restocking for " + result.restoredOfferSets()
                    + " loaded offer sets; saved physical stock was retained";
            case DISABLED -> "; saved personal inventory contents were retained";
        };
        source.sendSuccess(() -> Component.literal("Totem Villagers mode set to " + mode.id() + detail), true);
        return 1;
    }

    private static int showMode(net.minecraft.commands.CommandSourceStack source) {
        WorkBackedTradingMode mode = WorkBackedTradingSettingsSavedData.forServer(source.getServer()).settings().mode();
        source.sendSuccess(() -> Component.literal("Totem Villagers mode is " + mode.id()
                + (mode.enforcesWorkBackedTrading() ? " (work-backed trading enabled)" : " (vanilla trading authority active)")), false);
        return 1;
    }

    /** A short, intentional opt-in for a new world; it retains the normal empty-stock rollout. */
    private static int startPlaying(net.minecraft.commands.CommandSourceStack source) {
        WorkBackedTradingSettingsSavedData.forServer(source.getServer()).setMode(WorkBackedTradingMode.ENFORCED);
        WorldEnablementRuntime.ApplyResult result = WorldEnablementRuntime.apply(source.getServer(), WorkBackedTradingMode.ENFORCED);
        source.sendSuccess(() -> Component.literal("Totem Villagers started; initialised "
                + result.initialisedVillagers()
                + " loaded villagers. Each adult receives finite starter supplies; trade with them normally."), true);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> workZoneCommands() {
        var zone = Commands.literal("work-zone");
        var create = Commands.literal("create");
        var role = Commands.argument("role", StringArgumentType.word());
        var minimum = Commands.argument("minimum", BlockPosArgument.blockPos());
        var maximum = Commands.argument("maximum", BlockPosArgument.blockPos())
                .executes(context -> createWorkZone(context.getSource(),
                        StringArgumentType.getString(context, "role"),
                        BlockPosArgument.getLoadedBlockPos(context, "minimum"),
                        BlockPosArgument.getLoadedBlockPos(context, "maximum")));
        minimum.then(maximum);
        role.then(minimum);
        create.then(role);
        zone.then(create);

        var assign = Commands.literal("assign");
        var villager = Commands.argument("villager", UuidArgument.uuid());
        var assignedZone = Commands.argument("zone", UuidArgument.uuid())
                .executes(context -> assignWorkZone(context.getSource(),
                        UuidArgument.getUuid(context, "villager"),
                        UuidArgument.getUuid(context, "zone")));
        villager.then(assignedZone);
        assign.then(villager);
        zone.then(assign);
        zone.then(Commands.literal("status")
                .then(Commands.argument("villager", UuidArgument.uuid())
                        .executes(context -> showWorkZoneStatus(context.getSource(),
                                UuidArgument.getUuid(context, "villager")))));
        return zone;
    }

    private static int createWorkZone(net.minecraft.commands.CommandSourceStack source, String role, BlockPos minimum, BlockPos maximum)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!("totem:miner".equals(role) || "totem:lumberjack".equals(role) || "totem:builder".equals(role))) {
            source.sendFailure(Component.literal("Work Zone role must be totem:miner, totem:lumberjack, or totem:builder"));
            return 0;
        }
        var player = source.getPlayerOrException();
        try {
            var record = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(source.getServer()).createZone(role,
                    new dev.totem.villagers.worker.WorkZone(player.getUUID(), source.getLevel().dimension().identifier().toString(),
                            new dev.totem.villagers.worker.BlockCoordinate(minimum.getX(), minimum.getY(), minimum.getZ()),
                            new dev.totem.villagers.worker.BlockCoordinate(maximum.getX(), maximum.getY(), maximum.getZ())));
            source.sendSuccess(() -> Component.literal("Created " + role + " Work Zone " + record.id()), true);
            return 1;
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("Invalid Work Zone: " + invalid.getMessage()));
            return 0;
        }
    }

    private static int assignWorkZone(net.minecraft.commands.CommandSourceStack source, java.util.UUID villagerId, java.util.UUID zoneId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var data = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(source.getServer());
        var assignment = data.getAssignment(villagerId).orElse(null);
        if (assignment == null) {
            source.sendFailure(Component.literal("Assign the villager a Totem specialist role first"));
            return 0;
        }
        if (!data.assignZone(player.getUUID(), assignment, zoneId)) {
            source.sendFailure(Component.literal("That Work Zone is missing, belongs to another owner, or has another role"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Assigned Work Zone " + zoneId + " to villager " + villagerId), true);
        return 1;
    }

    private static int showWorkZoneStatus(net.minecraft.commands.CommandSourceStack source, java.util.UUID villagerId) {
        Villager villager = findLoadedVillager(source.getServer(), villagerId);
        if (villager == null) {
            source.sendFailure(Component.literal("That villager is not in a loaded chunk; no chunk was loaded"));
            return 0;
        }
        var professionKey = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        String professionId = professionKey == null ? "minecraft:none" : professionKey.toString();
        var assignments = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(source.getServer());
        var feedback = dev.totem.villagers.worker.WorkZoneFeedback.evaluate(villagerId, professionId,
                villager.level().dimension().identifier().toString(),
                new dev.totem.villagers.worker.BlockCoordinate(villager.getBlockX(), villager.getBlockY(), villager.getBlockZ()),
                assignments.getAssignment(villagerId), assignments.zoneSnapshot());
        if (feedback.isEmpty()) {
            source.sendFailure(Component.literal("That villager does not use a generic Work Zone"));
            return 0;
        }
        var status = feedback.orElseThrow();
        String message = "Work Zone for " + villagerId + " (" + status.roleId() + "): " + status.state().id();
        if (status.zoneId().isPresent()) {
            message += "; zone " + status.zoneId().orElseThrow();
        }
        if (status.zone().isPresent()) {
            var boundary = status.zone().orElseThrow();
            message += "; " + boundary.dimensionId() + " [" + boundary.minimum().x() + "," + boundary.minimum().y() + ","
                    + boundary.minimum().z() + "] to [" + boundary.maximum().x() + "," + boundary.maximum().y() + ","
                    + boundary.maximum().z() + "]";
        }
        String finalMessage = message;
        source.sendSuccess(() -> Component.literal(finalMessage), false);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> builderSiteCommands() {
        var builderSite = Commands.literal("builder-site");
        var register = Commands.literal("register");
        var builder = Commands.argument("builder", UuidArgument.uuid());
        var anchor = Commands.argument("anchor", BlockPosArgument.blockPos());
        var template = Commands.argument("template", StringArgumentType.word())
                .executes(context -> registerBuilderSite(context.getSource(),
                        UuidArgument.getUuid(context, "builder"),
                        BlockPosArgument.getLoadedBlockPos(context, "anchor"),
                        StringArgumentType.getString(context, "template")));
        anchor.then(template);
        builder.then(anchor);
        register.then(builder);
        builderSite.then(register);

        builderSite.then(Commands.literal("status")
                .then(Commands.argument("builder", UuidArgument.uuid())
                        .executes(context -> builderSiteStatus(context.getSource(),
                                UuidArgument.getUuid(context, "builder")))));
        builderSite.then(Commands.literal("cancel")
                .then(Commands.argument("builder", UuidArgument.uuid())
                        .executes(context -> cancelBuilderSite(context.getSource(),
                                UuidArgument.getUuid(context, "builder")))));
        return builderSite;
    }

    private static int registerBuilderSite(net.minecraft.commands.CommandSourceStack source, java.util.UUID builderId,
                                           BlockPos anchor, String rawTemplate)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        Villager builder = findLoadedVillager(source.getServer(), builderId);
        if (builder == null) {
            source.sendFailure(Component.literal("That Builder is not in a loaded chunk; no chunk was loaded"));
            return 0;
        }
        var assignments = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(source.getServer());
        var assignment = assignments.getAssignment(builderId).orElse(null);
        if (assignment == null || !"totem:builder".equals(assignment.roleId())) {
            source.sendFailure(Component.literal("Assign that villager the Totem Builder role first"));
            return 0;
        }
        var zone = assignment.workZoneId().flatMap(assignments::getZone).orElse(null);
        if (zone == null || !"totem:builder".equals(zone.roleId()) || !zone.zone().ownerId().equals(player.getUUID())
                || !zone.zone().dimensionId().equals(source.getLevel().dimension().identifier().toString())) {
            source.sendFailure(Component.literal("Assign that Builder an owned Builder Work Zone in this dimension first"));
            return 0;
        }
        Identifier templateId = Identifier.tryParse(rawTemplate);
        if (templateId == null || !VanillaVillageBlueprints.isAllowedTemplateId(templateId)) {
            source.sendFailure(Component.literal("Blueprint must be a vanilla village house: minecraft:village/<plains|desert|savanna|taiga|snowy>/houses/<name>"));
            return 0;
        }
        var blueprint = VanillaVillageBlueprints.resolve(source.getServer(), templateId, anchor).orElse(null);
        if (blueprint == null) {
            source.sendFailure(Component.literal("That template is missing or contains blocks without a player-placeable material"));
            return 0;
        }
        String dimensionId = source.getLevel().dimension().identifier().toString();
        var outsideZone = blueprint.blocks().stream().map(VanillaVillageBlueprints.BlueprintBlock::position)
                .filter(position -> !source.getLevel().isLoaded(position)
                        || !zone.zone().contains(dimensionId, new dev.totem.villagers.worker.BlockCoordinate(
                        position.getX(), position.getY(), position.getZ())))
                .findFirst();
        if (outsideZone.isPresent()) {
            source.sendFailure(Component.literal("Every blueprint block must be loaded and inside the Builder Work Zone; rejected "
                    + outsideZone.orElseThrow().toShortString()));
            return 0;
        }
        BuilderSite site = new BuilderSite(java.util.UUID.randomUUID(), player.getUUID(), builderId,
                templateId.toString(), dimensionId, anchor.asLong(), 0);
        if (!BuilderSiteSavedData.forServer(source.getServer()).registerOrReplace(site, player.getUUID())) {
            source.sendFailure(Component.literal("That Builder already has another owner's construction site"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Registered Builder blueprint " + templateId + " at "
                + anchor.toShortString() + " (" + blueprint.blocks().size() + " material-backed blocks)"), true);
        return 1;
    }

    private static int builderSiteStatus(net.minecraft.commands.CommandSourceStack source, java.util.UUID builderId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        BuilderSite site = BuilderSiteSavedData.forServer(source.getServer()).getByBuilder(builderId).orElse(null);
        if (site == null || !site.ownerId().equals(player.getUUID())) {
            source.sendFailure(Component.literal("You do not own a Builder construction site for that villager"));
            return 0;
        }
        Identifier templateId = Identifier.tryParse(site.templateId());
        var blueprint = templateId == null ? java.util.Optional.<VanillaVillageBlueprints.Blueprint>empty()
                : VanillaVillageBlueprints.resolve(source.getServer(), templateId, BlockPos.of(site.anchorPosition()));
        if (blueprint.isEmpty()) {
            source.sendFailure(Component.literal("Builder site is saved, but its vanilla template is currently unavailable"));
            return 0;
        }
        int total = blueprint.orElseThrow().blocks().size();
        source.sendSuccess(() -> Component.literal("Builder site " + site.id() + ": "
                + Math.min(site.nextBlockIndex(), total) + "/" + total + " blocks complete; template " + site.templateId()), false);
        return 1;
    }

    private static int cancelBuilderSite(net.minecraft.commands.CommandSourceStack source, java.util.UUID builderId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        if (!BuilderSiteSavedData.forServer(source.getServer()).removeByBuilder(builderId, player.getUUID())) {
            source.sendFailure(Component.literal("You do not own a Builder construction site for that villager"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Cancelled Builder construction site for " + builderId), true);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> needsCommands() {
        return Commands.literal("needs")
                .then(Commands.argument("villager", UuidArgument.uuid())
                        .executes(context -> showVillagerNeeds(context.getSource(),
                                UuidArgument.getUuid(context, "villager"))));
    }

    private static int showVillagerNeeds(net.minecraft.commands.CommandSourceStack source, java.util.UUID villagerId) {
        Villager villager = findLoadedVillager(source.getServer(), villagerId);
        if (villager == null) {
            source.sendFailure(Component.literal("That villager is not in a loaded chunk; no chunk was loaded"));
            return 0;
        }
        int foodLevel = VillagerNutrition.foodLevel(villager);
        int wallet = VillagerWorkInventorySavedData.forServer(source.getServer()).inventory(villagerId)
                .countMatchingItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD));
        source.sendSuccess(() -> Component.literal("Villager " + villagerId + " food " + foodLevel + "/"
                + VillagerNutrition.MAX_FOOD_LEVEL + (VillagerNutrition.isHungry(villager) ? " (hungry)" : " (fed)")
                + "; saturation " + String.format(java.util.Locale.ROOT, "%.1f", VillagerNutrition.saturationLevel(villager))
                + "; exhaustion " + String.format(java.util.Locale.ROOT, "%.2f", VillagerNutrition.exhaustionLevel(villager))
                + "; inventory " + wallet + " emeralds"), false);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> guardPostCommands() {
        var guardPost = Commands.literal("guard-post");
        var register = Commands.literal("register");
        var guard = Commands.argument("guard", UuidArgument.uuid());
        var pad = Commands.argument("construction_pad", BlockPosArgument.blockPos())
                .executes(context -> registerGuardPost(context.getSource(),
                        UuidArgument.getUuid(context, "guard"),
                        BlockPosArgument.getLoadedBlockPos(context, "construction_pad")));
        guard.then(pad);
        register.then(guard);
        guardPost.then(register);

        guardPost.then(Commands.literal("unregister")
                .then(Commands.argument("village", UuidArgument.uuid())
                        .executes(context -> unregisterGuardPost(context.getSource(),
                                UuidArgument.getUuid(context, "village")))));
        guardPost.then(Commands.literal("status")
                .then(Commands.argument("village", UuidArgument.uuid())
                        .executes(context -> showGuardPostStatus(context.getSource(),
                                UuidArgument.getUuid(context, "village")))));
        return guardPost;
    }

    private static int registerGuardPost(
            net.minecraft.commands.CommandSourceStack source,
            java.util.UUID guardId,
            BlockPos constructionPad
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        Villager guard = findLoadedVillager(source.getServer(), guardId);
        if (guard == null) {
            source.sendFailure(Component.literal("That Guard is not in a loaded chunk; no chunk was loaded"));
            return 0;
        }
        var assignments = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(source.getServer());
        var assignment = assignments.getAssignment(guardId).orElse(null);
        if (assignment == null || !"totem:guard".equals(assignment.roleId())) {
            source.sendFailure(Component.literal("Assign that villager the Totem Guard role first"));
            return 0;
        }
        if (!source.getLevel().isLoaded(constructionPad)) {
            source.sendFailure(Component.literal("The construction pad must be in a loaded chunk"));
            return 0;
        }
        var villages = dev.totem.villagers.guard.ManagedVillageSavedData.forServer(source.getServer());
        var existing = villages.getByGuard(guardId).orElse(null);
        if (existing != null && !existing.post().ownerId().equals(player.getUUID())) {
            source.sendFailure(Component.literal("That Guard already belongs to another owner's managed village"));
            return 0;
        }
        dev.totem.villagers.guard.GuardPost post = new dev.totem.villagers.guard.GuardPost(
                existing == null ? java.util.UUID.randomUUID() : existing.post().villageId(),
                player.getUUID(), source.getLevel().dimension().identifier().toString(), constructionPad.asLong());
        if (existing != null && existing.construction().isPresent() && !existing.post().equals(post)) {
            source.sendFailure(Component.literal("Cancel the active Guard construction before moving its Guard Post"));
            return 0;
        }
        dev.totem.villagers.guard.ManagedVillageState next = new dev.totem.villagers.guard.ManagedVillageState(
                post, guardId,
                existing == null ? java.util.Set.of() : existing.managedGolemIds(),
                existing == null ? java.util.Optional.empty() : existing.construction());
        if (!villages.registerOrUpdate(next, player.getUUID())) {
            source.sendFailure(Component.literal("Could not register that Guard Post"));
            return 0;
        }
        assignments.putAssignment(assignment.withManagedVillage(java.util.Optional.of(post.villageId())));
        source.sendSuccess(() -> Component.literal("Registered Guard Post village " + post.villageId()
                + " at " + constructionPad.toShortString()), true);
        return 1;
    }

    private static int unregisterGuardPost(net.minecraft.commands.CommandSourceStack source, java.util.UUID villageId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var villages = dev.totem.villagers.guard.ManagedVillageSavedData.forServer(source.getServer());
        var existing = villages.get(villageId).orElse(null);
        if (existing == null || !villages.remove(villageId, player.getUUID())) {
            source.sendFailure(Component.literal("You do not own that managed Guard village"));
            return 0;
        }
        var assignments = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(source.getServer());
        assignments.getAssignment(existing.guardVillagerId())
                .filter(assignment -> assignment.managedVillageId().filter(villageId::equals).isPresent())
                .ifPresent(assignment -> assignments.putAssignment(assignment.withManagedVillage(java.util.Optional.empty())));
        source.sendSuccess(() -> Component.literal("Unregistered Guard Post village " + villageId), true);
        return 1;
    }

    private static int showGuardPostStatus(net.minecraft.commands.CommandSourceStack source, java.util.UUID villageId) {
        var village = dev.totem.villagers.guard.ManagedVillageSavedData.forServer(source.getServer()).get(villageId).orElse(null);
        if (village == null) {
            source.sendFailure(Component.literal("No managed Guard village has that id"));
            return 0;
        }
        var feedback = dev.totem.villagers.guard.GuardPostFeedback.evaluate(source.getServer(), village);
        StringBuilder message = new StringBuilder("Guard Post ").append(villageId).append(": ")
                .append(feedback.state().id()).append("; defence ").append(feedback.managedGolems())
                .append('/').append(feedback.demand().targetGolems()).append(" golems; threats ")
                .append(feedback.demand().nearbyThreatCount());
        feedback.post().ifPresent(post -> {
            BlockPos pad = BlockPos.of(post.packedConstructionPad());
            message.append("; ").append(post.dimensionId()).append(" [").append(pad.getX()).append(',')
                    .append(pad.getY()).append(',').append(pad.getZ()).append(']');
        });
        feedback.construction().ifPresent(progress -> {
            message.append("; construction ").append(progress.orderId()).append(' ')
                    .append(progress.placedSteps()).append('/').append(progress.totalSteps());
            village.construction().ifPresent(construction -> message.append("; reserved ")
                    .append(construction.reservedInputs().stream()
                            .map(input -> input.itemId() + " x" + input.count())
                            .collect(java.util.stream.Collectors.joining(", "))));
        });
        String finalMessage = message.toString();
        source.sendSuccess(() -> Component.literal(finalMessage), false);
        return 1;
    }

    private static int assignSpecialistRole(net.minecraft.commands.CommandSourceStack source, java.util.UUID villagerId, String value) {
        Identifier roleId = Identifier.tryParse(value);
        if (roleId == null || !(roleId.equals(dev.totem.villagers.worker.TotemVillagerProfessions.MINER_ID)
                || roleId.equals(dev.totem.villagers.worker.TotemVillagerProfessions.LUMBERJACK_ID)
                || roleId.equals(dev.totem.villagers.worker.TotemVillagerProfessions.BUILDER_ID)
                || roleId.equals(dev.totem.villagers.worker.TotemVillagerProfessions.GUARD_ID))) {
            source.sendFailure(Component.literal("Role must be totem:miner, totem:lumberjack, totem:builder, or totem:guard"));
            return 0;
        }
        Villager villager = findLoadedVillager(source.getServer(), villagerId);
        if (villager == null) {
            source.sendFailure(Component.literal("That villager is not in a loaded chunk; no chunk was loaded"));
            return 0;
        }
        var profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(roleId);
        if (profession == null) {
            source.sendFailure(Component.literal("Totem specialist profession is unavailable"));
            return 0;
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        dev.totem.villagers.work.VillagerWorkSavedData.forServer(source.getServer()).getOrCreate(villagerId);
        dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(source.getServer()).putAssignment(
                new dev.totem.villagers.worker.WorkerAssignment(villagerId, roleId.toString(),
                        java.util.Optional.empty(), java.util.Optional.empty()));
        source.sendSuccess(() -> Component.literal("Assigned " + roleId + " to villager " + villagerId), true);
        return 1;
    }

    private static Villager findLoadedVillager(net.minecraft.server.MinecraftServer server, java.util.UUID villagerId) {
        for (var level : server.getAllLevels()) {
            for (Villager villager : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(Villager.class),
                    candidate -> candidate.getUUID().equals(villagerId))) {
                return villager;
            }
        }
        return null;
    }
}
