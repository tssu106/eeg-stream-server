package eeg.edf;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EdfReaderTest {

    private static final Path EDF_PATH = Path.of("data", "SC4001E0-PSG.edf");
    private static final double DELTA = 1e-6;

    @BeforeAll
    static void checkFixtureFilePresent() {
        assumeTrue(Files.exists(EDF_PATH), "EDF fixture file not present at " + EDF_PATH + ", skipping");
    }

    @Test
    void headerMatchesExpected() throws IOException {
        ExpectedHeader expected = loadJson("/expected_header.json", ExpectedHeader.class);

        try (EdfReader reader = new EdfReader(EDF_PATH)) {
            EdfHeader header = reader.header();

            assertEquals(expected.numRecords, header.numRecords());
            assertEquals(expected.recordDuration, header.recordDuration(), DELTA);
            assertEquals(expected.numSignals, header.numSignals());
            assertEquals(expected.headerBytes, header.headerBytes());
            assertEquals(expected.signals.size(), header.signals().size());

            for (ExpectedSignal expectedSignal : expected.signals) {
                EdfSignalHeader actual = header.signal(expectedSignal.index);
                assertEquals(expectedSignal.label, actual.label());
                assertEquals(expectedSignal.dimension, actual.dimension());
                assertEquals(expectedSignal.physMin, actual.physMin(), DELTA);
                assertEquals(expectedSignal.physMax, actual.physMax(), DELTA);
                assertEquals(expectedSignal.digMin, actual.digMin());
                assertEquals(expectedSignal.digMax, actual.digMax());
                assertEquals(expectedSignal.samplesPerRecord, actual.samplesPerRecord());
                assertEquals(expectedSignal.sfreq, actual.sfreq(), DELTA);
            }
        }
    }

    @ParameterizedTest(name = "channel {0} digital samples match MNE ground truth")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void digitalSamplesMatchExpected(int channel) throws IOException {
        int[] expected = loadIntCsv("/expected_ch" + channel + "_digital.csv");

        try (EdfReader reader = new EdfReader(EDF_PATH)) {
            EdfSignalHeader signal = reader.header().signal(channel);
            double durationSec = expected.length / signal.sfreq();
            int[] actual = reader.readDigitalSamples(channel, 0.0, durationSec);

            assertEquals(expected.length, actual.length);
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], actual[i], "channel " + channel + " sample mismatch at index " + i);
            }
        }
    }

    @ParameterizedTest(name = "channel {0} physical samples match MNE ground truth")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void physicalSamplesMatchExpected(int channel) throws IOException {
        double[] expected = loadDoubleCsv("/expected_ch" + channel + "_physical.csv");

        try (EdfReader reader = new EdfReader(EDF_PATH)) {
            EdfSignalHeader signal = reader.header().signal(channel);
            double durationSec = expected.length / signal.sfreq();
            double[] actual = reader.readPhysicalSamples(channel, 0.0, durationSec);

            assertEquals(expected.length, actual.length);
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], actual[i], DELTA, "channel " + channel + " sample mismatch at index " + i);
            }
        }
    }

    private static <T> T loadJson(String resourcePath, Class<T> type) throws IOException {
        try (var in = EdfReaderTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(reader, type);
            }
        }
    }

    private static int[] loadIntCsv(String resourcePath) throws IOException {
        List<String> lines = readLines(resourcePath);
        int[] values = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            values[i] = Integer.parseInt(lines.get(i));
        }
        return values;
    }

    private static double[] loadDoubleCsv(String resourcePath) throws IOException {
        List<String> lines = readLines(resourcePath);
        double[] values = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            values[i] = Double.parseDouble(lines.get(i));
        }
        return values;
    }

    private static List<String> readLines(String resourcePath) throws IOException {
        try (var in = EdfReaderTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
            return lines;
        }
    }

    private static class ExpectedHeader {
        int numRecords;
        double recordDuration;
        int numSignals;
        int headerBytes;
        List<ExpectedSignal> signals;
    }

    private static class ExpectedSignal {
        int index;
        String label;
        String dimension;
        double physMin;
        double physMax;
        int digMin;
        int digMax;
        int samplesPerRecord;
        double sfreq;
    }
}
