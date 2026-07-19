package eeg.server;

final class Json {

    private Json() {
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String quote(String s) {
        return "\"" + escape(s) + "\"";
    }

    static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    static String numberArray(double[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(number(values[i]));
        }
        return sb.append("]").toString();
    }
}
