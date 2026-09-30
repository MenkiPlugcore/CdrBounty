package store.cadera.cdrbounty.tracking;

public final class TrackerAccuracy {
    private TrackerAccuracy() {
    }

    public static int maxOffset(double distance,
                                int closeMaxDistance,
                                int mediumMaxDistance,
                                int farMaxDistance,
                                int closeOffset,
                                int mediumOffset,
                                int farOffset,
                                int veryFarOffset) {
        if (distance <= closeMaxDistance) return Math.max(0, closeOffset);
        if (distance <= mediumMaxDistance) return Math.max(0, mediumOffset);
        if (distance <= farMaxDistance) return Math.max(0, farOffset);
        return Math.max(0, veryFarOffset);
    }
}
