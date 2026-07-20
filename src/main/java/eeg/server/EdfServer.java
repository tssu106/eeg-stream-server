package eeg.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import eeg.dsp.ButterworthFilter;
import eeg.edf.EdfHeader;
import eeg.edf.EdfReader;
import eeg.edf.EdfSignalHeader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EdfServer {

    private static final int MAX_FILES_LISTED = 10;
    private static final Pattern SEX_PATTERN = Pattern.compile("\\b([MF])\\b");
    private static final Pattern AGE_PATTERN = Pattern.compile("(\\d{1,3})\\s*[Yy][Rr]");

    private static final int FILTER_ORDER = 4;
    private static final double FILTER_LOW_HZ = 0.5;
    private static final double FILTER_HIGH_HZ = 40.0;
    private static final double MIN_SFREQ_FOR_FILTER = 4.0;
    private static final double WARMUP_SEC = 5.0;

    private static boolean canFilter(double sfreq) {
        return sfreq >= MIN_SFREQ_FOR_FILTER;
    }

    private static double effectiveHighHz(double sfreq) {
        return Math.min(FILTER_HIGH_HZ, sfreq * 0.45);
    }

    private static final int DISPLAY_RANGE_SAMPLE_WINDOWS = 8;
    private static final double DISPLAY_RANGE_WINDOW_SEC = 10.0;

    /**
     * Estimates a sensible fixed y-axis range from actual data (1st-99th percentile across
     * several windows spread over the recording), instead of the channel's full hardware
     * calibration range. Some channels (e.g. limb-movement EMG) only use a small fraction of
     * their calibrated range most of the time, which makes a physMin/physMax-based axis look flat.
     */
    private double[] computeDisplayRange(EdfReader reader, EdfSignalHeader signal, double totalDurationSec) throws IOException {
        double windowSec = Math.min(DISPLAY_RANGE_WINDOW_SEC, totalDurationSec);
        double usableSpan = Math.max(0, totalDurationSec - windowSec);

        List<Double> samples = new ArrayList<>();
        for (int w = 0; w < DISPLAY_RANGE_SAMPLE_WINDOWS; w++) {
            double t = usableSpan <= 0 ? 0 : usableSpan * w / (DISPLAY_RANGE_SAMPLE_WINDOWS - 1);
            double[] chunk = reader.readPhysicalSamples(signal.index(), t, windowSec);
            for (double v : chunk) samples.add(v);
        }
        if (samples.isEmpty()) {
            return new double[]{signal.physMin(), signal.physMax()};
        }

        Collections.sort(samples);
        int n = samples.size();
        double p1 = samples.get((int) (n * 0.01));
        double p99 = samples.get(Math.min(n - 1, (int) (n * 0.99)));
        if (p99 - p1 < 1e-9) {
            return new double[]{signal.physMin(), signal.physMax()};
        }

        double pad = (p99 - p1) * 0.15;
        double lo = Math.max(signal.physMin(), p1 - pad);
        double hi = Math.min(signal.physMax(), p99 + pad);
        return new double[]{lo, hi};
    }

    private final Path dataDir;
    private final String defaultFileName;
    private final Map<String, EdfReader> readers = new ConcurrentHashMap<>();

    public EdfServer(Path dataDir, String defaultFileName) {
        this.dataDir = dataDir;
        this.defaultFileName = defaultFileName;
    }

    public static void main(String[] args) throws IOException {
        Path edfPath = args.length > 0 ? Path.of(args[0]) : Path.of("data", "SC4001E0-PSG.edf");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        Path dataDir = edfPath.toAbsolutePath().getParent();
        EdfServer server = new EdfServer(dataDir, edfPath.getFileName().toString());
        server.start(port);

        System.out.println("EDF server listening on http://localhost:" + port + " (default file: " + edfPath + ")");
    }

    private void start(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/api/files", new FilesHandler());
        httpServer.createContext("/api/header", new HeaderHandler());
        httpServer.createContext("/api/samples", new SamplesHandler());
        httpServer.createContext("/api/stream", new StreamHandler());
        httpServer.createContext("/api/annotations", new AnnotationsHandler());
        httpServer.createContext("/", new StaticHandler());
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
    }

    private EdfReader getReader(String filename) throws IOException {
        try {
            return readers.computeIfAbsent(filename, f -> {
                try {
                    return new EdfReader(dataDir.resolve(f));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private List<String> listEdfFiles() throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.edf")) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        }
        names.sort(Comparator.naturalOrder());
        if (names.size() > MAX_FILES_LISTED) {
            names = names.subList(0, MAX_FILES_LISTED);
        }
        return names;
    }

    private record SignalMeta(String category, String korean) {}

    private static final Pattern EEG_ELECTRODE = Pattern.compile(
            "(?i)^(FP1|FP2|F3|F4|F7|F8|FZ|C3|C4|CZ|P3|P4|PZ|O1|O2|T3|T4|T5|T6|T7|T8|P7|P8|A1|A2|M1|M2)$");

    private static SignalMeta classify(String label) {
        String l = label.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("eeg")) return new SignalMeta("EEG", "뇌파");
        if (l.contains("eog") || l.contains("roc") || l.contains("loc")) return new SignalMeta("EOG", "안구운동");
        if (l.contains("ecg") || l.contains("ekg")) return new SignalMeta("ECG", "심전도");
        if (l.contains("tib")) return new SignalMeta("EMG", "다리근전도");
        if (l.contains("emg")) return new SignalMeta("EMG", "근전도");
        if (l.contains("resp")) return new SignalMeta("Resp", "호흡");
        if (l.contains("flusso")) return new SignalMeta("Resp", "호흡(기류)");
        if (l.contains("torace") || l.contains("thora")) return new SignalMeta("Resp", "호흡(흉부)");
        if (l.contains("dome") || l.contains("abdo")) return new SignalMeta("Resp", "호흡(복부)");
        if (l.contains("temp")) return new SignalMeta("Temp", "체온");
        if (l.contains("event")) return new SignalMeta("Event", "이벤트 마커");
        if (l.equals("sao2") || l.contains("spo2")) return new SignalMeta("SpO2", "산소포화도");
        if (l.equals("hr")) return new SignalMeta("HR", "심박수");
        if (l.contains("pleth")) return new SignalMeta("Pleth", "맥파");
        if (l.equals("mic")) return new SignalMeta("Mic", "코골이 소리");

        String[] parts = label.split("[-–]");
        if (parts.length == 2 && EEG_ELECTRODE.matcher(parts[0].trim()).matches()
                && EEG_ELECTRODE.matcher(parts[1].trim()).matches()) {
            return new SignalMeta("EEG", "뇌파");
        }
        return new SignalMeta("Other", null);
    }

    private static String extractSex(String patientId) {
        Matcher m = SEX_PATTERN.matcher(patientId);
        return m.find() ? m.group(1) : null;
    }

    private static Integer extractAge(String patientId) {
        Matcher m = AGE_PATTERN.matcher(patientId);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static String formatStartDate(String startDate) {
        // EDF start date is "dd.mm.yy"
        String[] parts = startDate.split("\\.");
        if (parts.length != 3) return startDate;
        int yy = Integer.parseInt(parts[2]);
        int fullYear = yy < 85 ? 2000 + yy : 1900 + yy; // EDF spec clipdate heuristic
        return String.format("%04d-%s-%s", fullYear, parts[1], parts[0]);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static double[] readFilteredSamples(EdfReader reader, int channel, double sfreq,
                                                 double start, double duration) throws IOException {
        double warmup = Math.min(WARMUP_SEC, start);
        double[] raw = reader.readPhysicalSamples(channel, start - warmup, duration + warmup);

        ButterworthFilter filter = ButterworthFilter.designBandpass(FILTER_ORDER, FILTER_LOW_HZ, effectiveHighHz(sfreq), sfreq);
        double[] filteredAll = filter.apply(raw, filter.newState());

        int targetCount = (int) Math.round(duration * sfreq);
        double[] result = new double[targetCount];
        System.arraycopy(filteredAll, filteredAll.length - targetCount, result, 0, targetCount);
        return result;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key, value);
        }
        return params;
    }

    private class FilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            List<String> names = listEdfFiles();
            if (names.isEmpty()) {
                names = List.of(defaultFileName);
            }

            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) json.append(",");
                String name = names.get(i);
                EdfHeader header = getReader(name).header();
                String sex = extractSex(header.patientId());
                Integer age = extractAge(header.patientId());
                String date = formatStartDate(header.startDate());

                json.append("{");
                json.append("\"name\":").append(Json.quote(name)).append(",");
                json.append("\"sex\":").append(sex == null ? "null" : Json.quote(sex)).append(",");
                json.append("\"age\":").append(age == null ? "null" : age).append(",");
                json.append("\"recordingDate\":").append(Json.quote(date)).append(",");
                json.append("\"numSignals\":").append(header.numSignals());
                json.append("}");
            }
            json.append("]");

            sendJson(exchange, 200, json.toString());
        }
    }

    private class HeaderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String file = params.getOrDefault("file", defaultFileName);
            EdfReader reader = getReader(file);
            EdfHeader header = reader.header();
            double totalDurationSec = header.numRecords() * header.recordDuration();

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"file\":").append(Json.quote(file)).append(",");
            json.append("\"numRecords\":").append(header.numRecords()).append(",");
            json.append("\"recordDuration\":").append(Json.number(header.recordDuration())).append(",");
            json.append("\"totalDurationSec\":").append(Json.number(totalDurationSec)).append(",");
            json.append("\"sex\":").append(extractSex(header.patientId()) == null ? "null" : Json.quote(extractSex(header.patientId()))).append(",");
            json.append("\"age\":").append(extractAge(header.patientId()) == null ? "null" : extractAge(header.patientId())).append(",");
            json.append("\"recordingDate\":").append(Json.quote(formatStartDate(header.startDate()))).append(",");
            json.append("\"signals\":[");
            for (int i = 0; i < header.signals().size(); i++) {
                if (i > 0) json.append(",");
                EdfSignalHeader s = header.signal(i);
                SignalMeta meta = classify(s.label());
                double[] displayRange = "SpO2".equals(meta.category())
                        ? new double[]{70.0, 100.0}
                        : computeDisplayRange(reader, s, totalDurationSec);
                json.append("{");
                json.append("\"index\":").append(s.index()).append(",");
                json.append("\"label\":").append(Json.quote(s.label())).append(",");
                json.append("\"unit\":").append(Json.quote(s.dimension())).append(",");
                json.append("\"sfreq\":").append(Json.number(s.sfreq())).append(",");
                json.append("\"physMin\":").append(Json.number(s.physMin())).append(",");
                json.append("\"physMax\":").append(Json.number(s.physMax())).append(",");
                json.append("\"displayMin\":").append(Json.number(displayRange[0])).append(",");
                json.append("\"displayMax\":").append(Json.number(displayRange[1])).append(",");
                json.append("\"category\":").append(Json.quote(meta.category())).append(",");
                json.append("\"koreanLabel\":").append(meta.korean() == null ? "null" : Json.quote(meta.korean()));
                json.append("}");
            }
            json.append("]}");

            sendJson(exchange, 200, json.toString());
        }
    }

    private class SamplesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String file = params.getOrDefault("file", defaultFileName);
            EdfReader reader = getReader(file);
            EdfHeader header = reader.header();
            double totalDurationSec = header.numRecords() * header.recordDuration();

            int channel;
            double start;
            double duration;
            try {
                channel = Integer.parseInt(params.get("channel"));
                start = Double.parseDouble(params.get("start"));
                duration = Double.parseDouble(params.get("duration"));
            } catch (NumberFormatException | NullPointerException e) {
                sendJson(exchange, 400, "{\"error\":\"invalid query parameters\"}");
                return;
            }

            if (channel < 0 || channel >= header.numSignals()) {
                sendJson(exchange, 400, "{\"error\":\"channel out of range\"}");
                return;
            }
            start = Math.max(0, Math.min(start, totalDurationSec));
            duration = Math.max(0, Math.min(duration, totalDurationSec - start));

            EdfSignalHeader signal = header.signal(channel);
            boolean wantFiltered = "true".equalsIgnoreCase(params.get("filtered"));
            boolean filterApplied = wantFiltered && canFilter(signal.sfreq());
            double[] values = filterApplied
                    ? readFilteredSamples(reader, channel, signal.sfreq(), start, duration)
                    : reader.readPhysicalSamples(channel, start, duration);

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"channel\":").append(channel).append(",");
            json.append("\"sfreq\":").append(Json.number(signal.sfreq())).append(",");
            json.append("\"start\":").append(Json.number(start)).append(",");
            json.append("\"filtered\":").append(filterApplied).append(",");
            json.append("\"values\":").append(Json.numberArray(values));
            json.append("}");

            sendJson(exchange, 200, json.toString());
        }
    }

    private class AnnotationsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String file = params.getOrDefault("file", defaultFileName);
            EdfReader reader = getReader(file);
            EdfHeader header = reader.header();

            String baseName = file.toLowerCase(java.util.Locale.ROOT).endsWith(".edf")
                    ? file.substring(0, file.length() - 4)
                    : file;
            Path annotationFile = dataDir.resolve(baseName + ".txt");
            List<SleepAnnotations.StageEntry> stages = Files.exists(annotationFile)
                    ? SleepAnnotations.parseSleepStages(annotationFile, header.startTime())
                    : List.of();

            int spo2Channel = -1;
            for (int i = 0; i < header.numSignals(); i++) {
                if ("SpO2".equals(classify(header.signal(i).label()).category())) {
                    spo2Channel = i;
                    break;
                }
            }
            List<SleepAnnotations.DesatEvent> desaturations = spo2Channel >= 0
                    ? SleepAnnotations.detectDesaturations(reader, header, spo2Channel)
                    : List.of();

            sendJson(exchange, 200, SleepAnnotations.toJson(stages, desaturations));
        }
    }

    private class StreamHandler implements HttpHandler {
        private static final int TICK_MS = 200;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            String file = params.getOrDefault("file", defaultFileName);
            EdfReader reader = getReader(file);
            EdfHeader header = reader.header();
            double totalDurationSec = header.numRecords() * header.recordDuration();

            int[] channels;
            double start;
            double speed;
            boolean wantFiltered;
            try {
                channels = java.util.Arrays.stream(params.getOrDefault("channels", "").split(","))
                        .filter(s -> !s.isBlank())
                        .mapToInt(Integer::parseInt)
                        .toArray();
                start = Double.parseDouble(params.getOrDefault("start", "0"));
                speed = Double.parseDouble(params.getOrDefault("speed", "1"));
                wantFiltered = "true".equalsIgnoreCase(params.get("filtered"));
            } catch (NumberFormatException e) {
                sendJson(exchange, 400, "{\"error\":\"invalid query parameters\"}");
                return;
            }
            if (channels.length == 0) {
                sendJson(exchange, 400, "{\"error\":\"channels required\"}");
                return;
            }
            for (int ch : channels) {
                if (ch < 0 || ch >= header.numSignals()) {
                    sendJson(exchange, 400, "{\"error\":\"channel out of range\"}");
                    return;
                }
            }
            speed = Math.max(0.1, Math.min(speed, 200));
            start = Math.max(0, Math.min(start, totalDurationSec));

            double[] sfreqs = new double[channels.length];
            long[] nextIndex = new long[channels.length];
            ButterworthFilter[] filters = new ButterworthFilter[channels.length];
            double[][] filterStates = new double[channels.length][];
            boolean[] filterApplied = new boolean[channels.length];
            for (int i = 0; i < channels.length; i++) {
                double sfreq = header.signal(channels[i]).sfreq();
                sfreqs[i] = sfreq;
                nextIndex[i] = Math.round(start * sfreq);

                filterApplied[i] = wantFiltered && canFilter(sfreq);
                if (filterApplied[i]) {
                    ButterworthFilter filter = ButterworthFilter.designBandpass(FILTER_ORDER, FILTER_LOW_HZ, effectiveHighHz(sfreq), sfreq);
                    double[] state = filter.newState();
                    double warmup = Math.min(WARMUP_SEC, start);
                    if (warmup > 0) {
                        double[] warmupRaw = reader.readPhysicalSamples(channels[i], start - warmup, warmup);
                        filter.apply(warmupRaw, state);
                    }
                    filters[i] = filter;
                    filterStates[i] = state;
                }
            }

            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);

            double position = start;
            try (OutputStream os = exchange.getResponseBody()) {
                while (position < totalDurationSec) {
                    double nextPosition = Math.min(position + TICK_MS / 1000.0 * speed, totalDurationSec);

                    StringBuilder json = new StringBuilder();
                    json.append("{\"channels\":{");
                    for (int i = 0; i < channels.length; i++) {
                        if (i > 0) json.append(",");
                        long endIndex = Math.round(nextPosition * sfreqs[i]);
                        long count = Math.max(0, endIndex - nextIndex[i]);
                        double sampleStart = nextIndex[i] / sfreqs[i];
                        double sampleDuration = count / sfreqs[i];
                        double[] values = count > 0
                                ? reader.readPhysicalSamples(channels[i], sampleStart, sampleDuration)
                                : new double[0];
                        if (filterApplied[i] && values.length > 0) {
                            values = filters[i].apply(values, filterStates[i]);
                        }
                        nextIndex[i] = endIndex;

                        json.append("\"").append(channels[i]).append("\":{");
                        json.append("\"sfreq\":").append(Json.number(sfreqs[i])).append(",");
                        json.append("\"start\":").append(Json.number(sampleStart)).append(",");
                        json.append("\"filtered\":").append(filterApplied[i]).append(",");
                        json.append("\"values\":").append(Json.numberArray(values));
                        json.append("}");
                    }
                    json.append("}}");

                    os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();

                    position = nextPosition;
                    try {
                        Thread.sleep(TICK_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                os.write("event: end\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                // client disconnected; nothing more to do
            }
        }
    }

    private static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                path = "/viewer.html";
            }
            String resourcePath = "/eeg/server" + path;

            try (InputStream in = EdfServer.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    byte[] notFound = "not found".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, notFound.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(notFound);
                    }
                    return;
                }
                byte[] bytes = in.readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }
}
