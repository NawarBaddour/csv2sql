package csv2sql;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming CSV reader following the RFC 4180 escaping rules: fields may be
 * wrapped in quotes, {@code ""} inside a quoted field is a literal quote, quoted
 * fields may contain delimiters and line breaks, and records may end with
 * {@code \n}, {@code \r\n} or {@code \r}.
 *
 * <p>Delimiter, quote and escape are all configurable. At most one record is
 * held at a time, so file size does not affect memory.</p>
 */
final class CsvReader implements Closeable {

    /** Passed as the escape character to mean "no escape character". */
    static final char NO_ESCAPE = 0;

    private static final char BOM = '﻿';
    private static final int EOF = -1;
    /** Distinct from EOF and from every char, so it is safe as a pushback slot. */
    private static final int NO_PUSHBACK = -2;

    private final Reader in;
    private final char delimiter;
    private final char quote;
    private final char escape;
    private final char[] buf = new char[8192];

    private int pos;
    private int limit;
    private boolean eof;
    private int pushback = NO_PUSHBACK;
    private boolean atStart = true;

    CsvReader(Reader in, char delimiter, char quote, char escape) {
        this.in = in;
        this.delimiter = delimiter;
        this.quote = quote;
        this.escape = escape;
    }

    /** @return the next record's fields, or {@code null} at end of input */
    List<String> read() throws IOException {
        int c = next();
        if (c == EOF) {
            return null;
        }
        if (atStart) {
            atStart = false;
            if (c == BOM && (c = next()) == EOF) {
                return null;
            }
        }

        List<String> fields = new ArrayList<String>();
        StringBuilder field = new StringBuilder();

        while (true) {
            c = c == quote ? readQuoted(field) : readPlain(field, c);
            fields.add(field.toString());
            field.setLength(0);

            if (c == delimiter) {
                c = next();
                if (c == EOF) {
                    fields.add(""); // record ends on a dangling delimiter
                    return fields;
                }
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    int n = next();
                    if (n != '\n' && n != EOF) {
                        unread(n);
                    }
                }
                return fields;
            } else {
                return fields; // EOF
            }
        }
    }

    /** Reads an unquoted field whose first character has already been read. */
    private int readPlain(StringBuilder out, int c) throws IOException {
        while (c != EOF && c != delimiter && c != '\n' && c != '\r') {
            if (escape != NO_ESCAPE && c == escape) {
                readEscaped(out);
            } else {
                out.append((char) c);
            }
            c = next();
        }
        return c;
    }

    /** Reads a quoted field whose opening quote has already been read. */
    private int readQuoted(StringBuilder out) throws IOException {
        while (true) {
            int c = next();
            if (c == EOF) {
                return EOF; // unterminated quote: keep what we have
            }
            if (escape != NO_ESCAPE && c == escape) {
                readEscaped(out);
                continue;
            }
            if (c != quote) {
                out.append((char) c);
                continue;
            }

            c = next();
            if (c == quote) {
                out.append(quote); // "" inside a quoted field
            } else if (c == EOF) {
                return EOF;
            } else if (c == delimiter || c == '\n' || c == '\r') {
                return c;
            } else {
                return skipToTerminator(); // junk between closing quote and delimiter
            }
        }
    }

    /**
     * Appends the character an escape character protects. A trailing escape at
     * end of input is taken literally.
     */
    private void readEscaped(StringBuilder out) throws IOException {
        int c = next();
        if (c == EOF) {
            out.append(escape);
        } else {
            out.append((char) c);
        }
    }

    private int skipToTerminator() throws IOException {
        int c;
        while ((c = next()) != EOF && c != delimiter && c != '\n' && c != '\r') {
            // discard
        }
        return c;
    }

    private int next() throws IOException {
        if (pushback != NO_PUSHBACK) {
            int c = pushback;
            pushback = NO_PUSHBACK;
            return c;
        }
        if (pos >= limit && !refill()) {
            return EOF;
        }
        return buf[pos++];
    }

    private boolean refill() throws IOException {
        if (eof) {
            return false;
        }
        limit = in.read(buf, 0, buf.length);
        pos = 0;
        if (limit <= 0) {
            eof = true;
            limit = 0;
            return false;
        }
        return true;
    }

    private void unread(int c) {
        pushback = c;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
