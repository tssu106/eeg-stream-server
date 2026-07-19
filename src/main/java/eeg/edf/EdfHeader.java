package eeg.edf;

import java.util.List;

public record EdfHeader(
        int numRecords,
        double recordDuration,
        int numSignals,
        int headerBytes,
        List<EdfSignalHeader> signals
) {
    public EdfSignalHeader signal(int index) {
        return signals.get(index);
    }
}
