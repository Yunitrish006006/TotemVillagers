package dev.totem.villagers.workshop;

/** Observable result used to produce the server-owned trade diagnostic. */
public enum WorkshopCommitResult {
    COMPLETED,
    INPUT_NOT_ACCEPTED,
    INPUTS_UNAVAILABLE,
    RETURN_UNAVAILABLE,
    JOB_SITE_REJECTED
}
