package dev.totem.villagers.schedule;

import dev.totem.villagers.work.ActiveWork;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;

import java.util.Comparator;
import java.util.Optional;

/**
 * One cancellable job per villager.  It only selects or advances already validated
 * candidates and deliberately leaves world/container mutation to a commit authority.
 */
public final class VillagerWorkScheduler {
    public WorkScheduleResult tick(WorkOrderCatalog catalog, VillagerWorkState state, WorkScheduleInput input) {
        if (!state.villagerId().equals(input.villagerId())) {
            throw new IllegalArgumentException("schedule input belongs to another villager");
        }
        Optional<ActiveWork> active = state.activeWork();
        if (active.isPresent()) {
            return advance(catalog, state, input, active.get());
        }
        if (unsafe(input)) {
            return new WorkScheduleResult(blocked(state, null, input, unsafeReason(input)), Optional.empty());
        }

        Optional<WorkCandidate> next = input.candidates().stream()
                .filter(candidate -> supports(catalog, candidate, input.professionId()))
                .filter(candidate -> stockNeedsWork(state, catalog.require(candidate.orderId())))
                .min(Comparator.comparingInt(WorkCandidate::priority).thenComparing(WorkCandidate::orderId));
        if (next.isEmpty()) {
            return new WorkScheduleResult(blocked(state, null, input, "no permitted source"), Optional.empty());
        }

        WorkCandidate candidate = next.get();
        ActiveWork started = new ActiveWork(candidate.orderId(), candidate.source(), input.gameTick(), 0, candidate.worldTarget());
        TradeDiagnostic diagnostic = new TradeDiagnostic(candidate.orderId(), candidate.source(), 0, "");
        return new WorkScheduleResult(state.withActiveWork(Optional.of(started), Optional.of(diagnostic)), Optional.empty());
    }

    private WorkScheduleResult advance(WorkOrderCatalog catalog, VillagerWorkState state, WorkScheduleInput input, ActiveWork active) {
        WorkOrder order;
        try {
            order = catalog.require(active.orderId());
        } catch (IllegalArgumentException missingOrder) {
            return new WorkScheduleResult(blocked(state, active, input, "order removed"), Optional.empty());
        }
        if (unsafe(input)) {
            return new WorkScheduleResult(blocked(state, active, input, unsafeReason(input)), Optional.empty());
        }
        if (!order.professionId().equals(input.professionId())) {
            return new WorkScheduleResult(blocked(state, active, input, "profession changed"), Optional.empty());
        }
        boolean sourceStillValid = input.candidates().stream()
                .anyMatch(candidate -> candidate.orderId().equals(active.orderId())
                        && candidate.source() == active.source()
                        && candidate.worldTarget().equals(active.worldTarget()));
        if (!sourceStillValid) {
            return new WorkScheduleResult(blocked(state, active, input, "source changed"), Optional.empty());
        }
        if (!input.atWorkLocation()) {
            TradeDiagnostic travelling = new TradeDiagnostic(active.orderId(), active.source(), active.elapsedTicks(), "travelling to job site");
            return new WorkScheduleResult(state.withActiveWork(Optional.of(active), Optional.of(travelling)), Optional.empty());
        }
        int elapsed = Math.min(order.workTicks(), active.elapsedTicks() + 1);
        ActiveWork progressed = new ActiveWork(active.orderId(), active.source(), active.startedAtTick(), elapsed, active.worldTarget());
        TradeDiagnostic diagnostic = new TradeDiagnostic(active.orderId(), active.source(), elapsed, "");
        VillagerWorkState progressedState = state.withActiveWork(Optional.of(progressed), Optional.of(diagnostic));
        return new WorkScheduleResult(progressedState, elapsed == order.workTicks() ? Optional.of(order) : Optional.empty());
    }

    private static boolean supports(WorkOrderCatalog catalog, WorkCandidate candidate, String professionId) {
        WorkOrder order;
        try {
            order = catalog.require(candidate.orderId());
        } catch (IllegalArgumentException missingOrder) {
            return false;
        }
        return order.professionId().equals(professionId) && order.allowedSources().contains(candidate.source());
    }

    private static boolean stockNeedsWork(VillagerWorkState state, WorkOrder order) {
        int current = order.outputKey().isBaseItem()
                ? state.merchantStock().getOrDefault(order.output().itemId(), 0)
                : state.variantMerchantStock().getOrDefault(order.outputKey(), 0);
        return current <= order.stockCap() - order.output().count();
    }

    private static boolean unsafe(WorkScheduleInput input) {
        return !input.alive() || !input.chunkLoaded() || input.inDanger() || input.sleeping() || input.raidActive() || !input.jobSiteValid();
    }

    private static String unsafeReason(WorkScheduleInput input) {
        if (!input.alive()) return "villager removed";
        if (!input.chunkLoaded()) return "chunk unloaded";
        if (input.inDanger()) return "danger";
        if (input.sleeping()) return "sleep";
        if (input.raidActive()) return "raid";
        return "job site changed";
    }

    private static VillagerWorkState blocked(VillagerWorkState state, ActiveWork active, WorkScheduleInput input, String reason) {
        if (active == null) {
            return state.withActiveWork(Optional.empty(), Optional.empty());
        }
        return state.withActiveWork(Optional.empty(), Optional.of(new TradeDiagnostic(
                active.orderId(), active.source(), active.elapsedTicks(), reason)));
    }
}
