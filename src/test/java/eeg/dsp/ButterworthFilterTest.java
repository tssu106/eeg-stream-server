package eeg.dsp;

import eeg.edf.EdfReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ButterworthFilterTest {

    private static final Path EDF_PATH = Path.of("data", "SC4001E0-PSG.edf");
    private static final double DELTA = 1e-6;

    @BeforeAll
    static void checkFixtureFilePresent() {
        assumeTrue(Files.exists(EDF_PATH), "EDF fixture file not present at " + EDF_PATH + ", skipping");
    }

    @Test
    void designBandpassMatchesScipyButterCoefficients() {
        // scipy.signal.butter(4, [0.5, 40], btype='bandpass', fs=100.0)
        double[] expectedB = {
                0.41433450842934594, 0.0, -1.6573380337173838, 0.0,
                2.4860070505760756, 0.0, -1.6573380337173838, 0.0, 0.41433450842934594
        };
        double[] expectedA = {
                1.0, -1.5531413924368378, -1.2001834773273879, 1.8641081944818088,
                1.3824019055399742, -1.1805056337537874, -0.7505906306076238,
                0.2662121284266984, 0.17170549898199317
        };

        ButterworthFilter filter = ButterworthFilter.designBandpass(4, 0.5, 40.0, 100.0);

        assertArrayEquals(expectedB, filter.b, DELTA);
        assertArrayEquals(expectedA, filter.a, DELTA);
    }

    @Test
    void filteredSamplesMatchMneGroundTruth() throws IOException {
        double[] expected = loadDoubleCsv("/expected_ch0_filtered.csv");

        try (EdfReader reader = new EdfReader(EDF_PATH)) {
            double sfreq = reader.header().signal(0).sfreq();
            double durationSec = expected.length / sfreq;
            double[] raw = reader.readPhysicalSamples(0, 0.0, durationSec);

            ButterworthFilter filter = ButterworthFilter.designBandpass(4, 0.5, 40.0, sfreq);
            double[] filtered = filter.apply(raw, filter.newState());

            assertEquals(expected.length, filtered.length);
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], filtered[i], DELTA, "sample mismatch at index " + i);
            }
        }
    }

    @Test
    void streamingChunksMatchSingleShotFiltering() {
        ButterworthFilter filter = ButterworthFilter.designBandpass(4, 0.5, 40.0, 100.0);
        double[] x = new double[500];
        for (int i = 0; i < x.length; i++) {
            x[i] = Math.sin(2 * Math.PI * 3 * i / 100.0) + 0.3 * Math.sin(2 * Math.PI * 45 * i / 100.0);
        }

        double[] wholeState = filter.newState();
        double[] wholeOutput = filter.apply(x, wholeState);

        double[] chunkedState = filter.newState();
        double[] chunkedOutput = new double[x.length];
        int pos = 0;
        int[] chunkSizes = {37, 100, 63, 200, 100};
        for (int size : chunkSizes) {
            double[] chunk = new double[size];
            System.arraycopy(x, pos, chunk, 0, size);
            double[] out = filter.apply(chunk, chunkedState);
            System.arraycopy(out, 0, chunkedOutput, pos, size);
            pos += size;
        }

        assertArrayEquals(wholeOutput, chunkedOutput, 1e-9);
    }

    private static double[] loadDoubleCsv(String resourcePath) throws IOException {
        List<String> lines = new ArrayList<>();
        try (var in = ButterworthFilterTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
        }
        double[] values = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            values[i] = Double.parseDouble(lines.get(i));
        }
        return values;
    }
}
