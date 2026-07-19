package eeg.edf;

public record EdfSignalHeader(
        int index,
        String label,
        String dimension,
        double physMin,
        double physMax,
        int digMin,
        int digMax,
        int samplesPerRecord,
        double sfreq
) {
    public double gain() {
        return (physMax - physMin) / (digMax - digMin);
    }

    public double offset() {
        return physMin - digMin * gain();
    }

    public double toPhysical(int digitalValue) {
        return digitalValue * gain() + offset();
    }
}
