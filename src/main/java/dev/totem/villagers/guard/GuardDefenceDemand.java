package dev.totem.villagers.guard;

/** Pure, bounded demand calculation for one configured Guard Post. */
public record GuardDefenceDemand(int residentCount, int nearbyThreatCount, int targetGolems) {
    public static final int THREATS_PER_EXTRA_GOLEM = 4;
    public static final int MAX_TARGET_GOLEMS = 3;

    public GuardDefenceDemand {
        if (residentCount < 0 || nearbyThreatCount < 0 || targetGolems < 0 || targetGolems > MAX_TARGET_GOLEMS) {
            throw new IllegalArgumentException("Guard defence counts are out of range");
        }
    }

    public static GuardDefenceDemand fromCounts(int residentCount, int nearbyThreatCount) {
        if (residentCount < 0 || nearbyThreatCount < 0) {
            throw new IllegalArgumentException("Guard defence counts cannot be negative");
        }
        int target = residentCount == 0 ? 0
                : Math.min(MAX_TARGET_GOLEMS, 1 + (nearbyThreatCount + THREATS_PER_EXTRA_GOLEM - 1) / THREATS_PER_EXTRA_GOLEM);
        return new GuardDefenceDemand(residentCount, nearbyThreatCount, target);
    }
}
