package csv2sql;

import java.util.ArrayList;
import java.util.List;

/** Guesses the delimiter of a CSV file by trial-splitting a sample of it. */
final class Delimiters {

    private static final char[] CANDIDATES = {',', '\t', ';', '|'};
    private static final int MAX_LINES = 20;
    private static final int MAX_FIELDS = 512;

    /**
     * Picks whichever candidate splits the most lines into the most fields.
     * Ties fall back to the earlier candidate, so comma wins on a file that
     * uses both it and something more exotic.
     */
    static char detect(String sample) {
        char best = CANDIDATES[0];
        long bestScore = -1;
        for (char candidate : CANDIDATES) {
            long score = score(sample, candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static long score(String sample, char delimiter) {
        int[] histogram = new int[MAX_FIELDS + 1];
        int lines = 0;
        for (int fieldCount : countFieldsPerLine(sample, delimiter)) {
            if (++lines > MAX_LINES) {
                break;
            }
            if (fieldCount >= 2 && fieldCount <= MAX_FIELDS) {
                histogram[fieldCount]++;
            }
        }
        for (int fields = MAX_FIELDS; fields >= 2; fields--) {
            if (histogram[fields] > 0) {
                return (long) histogram[fields] * fields;
            }
        }
        return 0;
    }

    /** Field count for each of the first {@link #MAX_LINES} lines, quotes honoured. */
    private static List<Integer> countFieldsPerLine(String sample, char delimiter) {
        List<Integer> counts = new ArrayList<Integer>();
        int n = sample.length();
        int c = 0;
        while (c < n && counts.size() < MAX_LINES) {
            int fields = 1;
            boolean quoted = false;
            while (c < n) {
                char ch = sample.charAt(c);
                if (quoted) {
                    if (ch == '"') {
                        if (c + 1 < n && sample.charAt(c + 1) == '"') {
                            c++;
                        } else {
                            quoted = false;
                        }
                    }
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == delimiter) {
                    fields++;
                } else if (ch == '\n' || ch == '\r') {
                    c++;
                    if (ch == '\r' && c < n && sample.charAt(c) == '\n') {
                        c++;
                    }
                    break;
                }
                c++;
            }
            if (fields > 1) {
                counts.add(fields);
            }
        }
        return counts;
    }

    private Delimiters() {
    }
}
