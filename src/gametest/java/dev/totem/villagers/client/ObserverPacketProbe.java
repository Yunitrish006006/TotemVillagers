package dev.totem.villagers.client;

/** Test-only counter for packets attempted by a read-only Observer screen. */
public final class ObserverPacketProbe {
    private static int sends;
    private ObserverPacketProbe() { }
    public static void reset() { sends = 0; }
    public static void record() { sends++; }
    public static int sends() { return sends; }
}
