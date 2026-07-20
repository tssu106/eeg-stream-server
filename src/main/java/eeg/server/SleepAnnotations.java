package eeg.server;

import eeg.edf.EdfHeader;
import eeg.edf.EdfReader;
import eeg.edf.EdfSignalHeader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses RemLogic sleep-stage annotation exports (as published alongside the CAP Sleep
 * Database) and detects SAO2 desaturation episodes directly from the signal, since this
 * dataset's public annotation export does not include scored apnea/hypopnea events.
 */
final class SleepAnnotations {

    record StageEntry(double start, double duration, String stage) {}

    record DesatEvent(double start, double end, double minValue) {}

    private static final double DESAT_THRESHOLD = 90.0;
    private static final double DESAT_MIN_DURATION_SEC = 10.0;
    // Readings this low are physiologically implausible for a live desaturation and almost
    // always mean the pulse oximeter probe lost contact, not that the patient is asphyxiating.
    private static final double SENSOR_ARTIFACT_FLOOR = 50.0;

    private SleepAnnotations() {
    }

    static List<StageEntry> parseSleepStages(Path txtFile, String edfStartTime) throws IOException {
        List<StageEntry> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(txtFile, StandardCharsets.UTF_8);
        for (String line : lines) {
            String[] parts = line.split("\t");
            if (parts.length < 5) continue;
            String event = parts[3].trim();
            if (!event.startsWith("SLEEP-")) continue;

            double duration;
            try {
                duration = Double.parseDouble(parts[4].trim());
            } catch (NumberFormatException e) {
                continue;
            }

            Double elapsed = toElapsedSeconds(edfStartTime, parts[2].trim());
            if (elapsed == null) continue;

            result.add(new StageEntry(elapsed, duration, mapStage(parts[0].trim())));
        }
        return result;
    }

    private static String mapStage(String code) {
        return switch (code) {
            case "W" -> "W";
            case "R" -> "REM";
            case "S1", "S2", "S3", "S4" -> code;
            case "MT" -> "MT";
            default -> code;
        };
    }

    /** Converts an "hh:mm:ss" annotation timestamp to seconds elapsed since the EDF's "hh.mm.ss" start time. */
    private static Double toElapsedSeconds(String edfStartTime, String annotTime) {
        int[] start = parseHms(edfStartTime, "\\.");
        int[] annot = parseHms(annotTime, ":");
        if (start == null || annot == null) return null;

        int startSec = start[0] * 3600 + start[1] * 60 + start[2];
        int annotSec = annot[0] * 3600 + annot[1] * 60 + annot[2];
        int diff = annotSec - startSec;
        if (diff < 0) diff += 24 * 3600;
        return (double) diff;
    }

    private static int[] parseHms(String s, String separatorRegex) {
        String[] p = s.split(separatorRegex);
        if (p.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<DesatEvent> detectDesaturations(EdfReader reader, EdfHeader header, int spo2Channel) throws IOException {
        List<DesatEvent> events = new ArrayList<>();
        EdfSignalHeader signal = header.signal(spo2Channel);
        double totalDurationSec = header.numRecords() * header.recordDuration();
        double[] values = reader.readPhysicalSamples(spo2Channel, 0, totalDurationSec);
        double sfreq = signal.sfreq();

        int i = 0;
        while (i < values.length) {
            boolean inRange = values[i] < DESAT_THRESHOLD && values[i] >= SENSOR_ARTIFACT_FLOOR;
            if (inRange) {
                int runStart = i;
                double minValue = values[i];
                while (i < values.length && values[i] < DESAT_THRESHOLD && values[i] >= SENSOR_ARTIFACT_FLOOR) {
                    minValue = Math.min(minValue, values[i]);
                    i++;
                }
                double startSec = runStart / sfreq;
                double endSec = i / sfreq;
                if (endSec - startSec >= DESAT_MIN_DURATION_SEC) {
                    events.add(new DesatEvent(startSec, endSec, minValue));
                }
            } else {
                i++;
            }
        }
        return events;
    }

    static String toJson(List<StageEntry> stages, List<DesatEvent> desaturations) {
        StringBuilder json = new StringBuilder();
        json.append("{\"sleepStages\":[");
        for (int i = 0; i < stages.size(); i++) {
            if (i > 0) json.append(",");
            StageEntry s = stages.get(i);
            json.append("{\"start\":").append(Json.number(s.start()))
                    .append(",\"duration\":").append(Json.number(s.duration()))
                    .append(",\"stage\":").append(Json.quote(s.stage()))
                    .append("}");
        }
        json.append("],\"desaturations\":[");
        for (int i = 0; i < desaturations.size(); i++) {
            if (i > 0) json.append(",");
            DesatEvent d = desaturations.get(i);
            json.append("{\"start\":").append(Json.number(d.start()))
                    .append(",\"end\":").append(Json.number(d.end()))
                    .append(",\"minValue\":").append(Json.number(d.minValue()))
                    .append("}");
        }
        json.append("]}");
        return json.toString();
    }
}
