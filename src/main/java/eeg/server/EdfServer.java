package eeg.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import eeg.edf.EdfHeader;
import eeg.edf.EdfReader;
import eeg.edf.EdfSignalHeader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class EdfServer {

    public static void main(String[] args) throws IOException {
        Path edfPath = args.length > 0 ? Path.of(args[0]) : Path.of("data", "SC4001E0-PSG.edf");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        EdfReader reader = new EdfReader(edfPath);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/header", new HeaderHandler(reader.header()));
        server.createContext("/api/samples", new SamplesHandler(reader));
        server.createContext("/api/stream", new StreamHandler(reader));
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("EDF server listening on http://localhost:" + port + " (" + edfPath + ")");
    }

    private static String categoryOf(String label) {
        String l = label.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("eeg")) return "EEG";
        if (l.contains("eog")) return "EOG";
        if (l.contains("emg")) return "EMG";
        if (l.contains("resp")) return "Resp";
        if (l.contains("temp")) return "Temp";
        if (l.contains("event")) return "Event";
        return "Other";
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
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

    private static class HeaderHandler implements HttpHandler {
        private final EdfHeader header;

        HeaderHandler(EdfHeader header) {
            this.header = header;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            double totalDurationSec = header.numRecords() * header.recordDuration();

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"numRecords\":").append(header.numRecords()).append(",");
            json.append("\"recordDuration\":").append(Json.number(header.recordDuration())).append(",");
            json.append("\"totalDurationSec\":").append(Json.number(totalDurationSec)).append(",");
            json.append("\"signals\":[");
            for (int i = 0; i < header.signals().size(); i++) {
                if (i > 0) json.append(",");
                EdfSignalHeader s = header.signal(i);
                json.append("{");
                json.append("\"index\":").append(s.index()).append(",");
                json.append("\"label\":").append(Json.quote(s.label())).append(",");
                json.append("\"unit\":").append(Json.quote(s.dimension())).append(",");
                json.append("\"sfreq\":").append(Json.number(s.sfreq())).append(",");
                json.append("\"physMin\":").append(Json.number(s.physMin())).append(",");
                json.append("\"physMax\":").append(Json.number(s.physMax())).append(",");
                json.append("\"category\":").append(Json.quote(categoryOf(s.label())));
                json.append("}");
            }
            json.append("]}");

            sendJson(exchange, 200, json.toString());
        }
    }

    private static class SamplesHandler implements HttpHandler {
        private final EdfReader reader;

        SamplesHandler(EdfReader reader) {
            this.reader = reader;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
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
            double[] values = reader.readPhysicalSamples(channel, start, duration);

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"channel\":").append(channel).append(",");
            json.append("\"sfreq\":").append(Json.number(signal.sfreq())).append(",");
            json.append("\"start\":").append(Json.number(start)).append(",");
            json.append("\"values\":").append(Json.numberArray(values));
            json.append("}");

            sendJson(exchange, 200, json.toString());
        }
    }

    private static class StreamHandler implements HttpHandler {
        private static final int TICK_MS = 200;

        private final EdfReader reader;

        StreamHandler(EdfReader reader) {
            this.reader = reader;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            EdfHeader header = reader.header();
            double totalDurationSec = header.numRecords() * header.recordDuration();

            int[] channels;
            double start;
            double speed;
            try {
                channels = java.util.Arrays.stream(params.getOrDefault("channels", "").split(","))
                        .filter(s -> !s.isBlank())
                        .mapToInt(Integer::parseInt)
                        .toArray();
                start = Double.parseDouble(params.getOrDefault("start", "0"));
                speed = Double.parseDouble(params.getOrDefault("speed", "1"));
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
            for (int i = 0; i < channels.length; i++) {
                double sfreq = header.signal(channels[i]).sfreq();
                sfreqs[i] = sfreq;
                nextIndex[i] = Math.round(start * sfreq);
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
                        nextIndex[i] = endIndex;

                        json.append("\"").append(channels[i]).append("\":{");
                        json.append("\"sfreq\":").append(Json.number(sfreqs[i])).append(",");
                        json.append("\"start\":").append(Json.number(sampleStart)).append(",");
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
