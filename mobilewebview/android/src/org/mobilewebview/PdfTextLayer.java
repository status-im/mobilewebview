package org.mobilewebview;

import java.util.ArrayList;
import java.util.List;

/**
 * A PDF page's text as runs to lay transparently over the page image, so text
 * selection and find-in-page land where the page draws the words. A run is
 * words the page draws side by side on one row; a line break, a gap wider than
 * the text is tall, a jump to another row or undrawn text starts a new run.
 * Pure Java, so the JVM tests state the layout rules.
 */
final class PdfTextLayer {
    private PdfTextLayer() { }

    /** Where the page draws the text between two indexes of its text, in page points. */
    interface Bounds {
        /** One {left, top, right, bottom} per row the text is drawn on; empty when it is not drawn. */
        List<float[]> of(int start, int end);
    }

    static final class Run {
        final String text;
        final float left;
        final float top;
        final float right;
        final float bottom;
        // What copied text puts before the next run: "\n", " " or nothing.
        final String separator;

        Run(String text, float[] rect, String separator) {
            this.text = text;
            this.left = rect[0];
            this.top = rect[1];
            this.right = rect[2];
            this.bottom = rect[3];
            this.separator = separator;
        }
    }

    static List<Run> runs(String pageText, Bounds bounds) {
        Builder builder = new Builder(pageText);
        int start = -1;
        for (int i = 0; i <= pageText.length(); i++) {
            boolean space = i == pageText.length() || Character.isWhitespace(pageText.charAt(i));
            if (!space && start < 0) {
                start = i;
            } else if (space && start >= 0) {
                addWord(builder, bounds, start, i);
                start = -1;
            }
        }
        return builder.finish();
    }

    /** Runs as the viewer page reads them: [[left, top, right, bottom, "text"(, separator)], ...]. */
    static String json(List<Run> runs) {
        StringBuilder sb = new StringBuilder(runs.size() * 64).append('[');
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            sb.append(i == 0 ? "[" : ",[")
              .append(PdfViewer.number(run.left)).append(',')
              .append(PdfViewer.number(run.top)).append(',')
              .append(PdfViewer.number(run.right)).append(',')
              .append(PdfViewer.number(run.bottom)).append(',')
              .append(PdfViewer.jsString(run.text));
            if (!run.separator.isEmpty()) {
                sb.append(',').append(PdfViewer.jsString(run.separator));
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    // A word the page draws in one piece is placed whole; one it draws on
    // several rows is placed character by character.
    private static void addWord(Builder builder, Bounds bounds, int start, int end) {
        List<float[]> rects = bounds.of(start, end);
        if (rects.size() <= 1) {
            builder.add(start, end, rects.isEmpty() ? null : rects.get(0));
            return;
        }
        for (int i = start; i < end; ) {
            int next = i + Character.charCount(Character.codePointAt(builder.mText, i));
            builder.add(i, next, union(bounds.of(i, next)));
            i = next;
        }
    }

    private static float[] union(List<float[]> rects) {
        if (rects.isEmpty()) {
            return null;
        }
        float[] u = rects.get(0).clone();
        for (float[] r : rects) {
            u[0] = Math.min(u[0], r[0]);
            u[1] = Math.min(u[1], r[1]);
            u[2] = Math.max(u[2], r[2]);
            u[3] = Math.max(u[3], r[3]);
        }
        return u;
    }

    // Whether two rectangles share a row: they overlap for at least half the shorter one's height.
    private static boolean sameRow(float[] a, float[] b) {
        float overlap = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        return overlap >= Math.min(a[3] - a[1], b[3] - b[1]) / 2;
    }

    private static boolean breaksLine(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    private static final class Builder {
        private final String mText;
        // Each run so far: its text's {start, end} and the rectangle it is drawn in.
        private final List<int[]> mSpans = new ArrayList<>();
        private final List<float[]> mRects = new ArrayList<>();
        private boolean mOpen = false;

        Builder(String text) {
            mText = text;
        }

        // The text between start and end, drawn in rect (null when not drawn).
        void add(int start, int end, float[] rect) {
            if (rect == null) {
                mOpen = false;
                return;
            }
            int last = mSpans.size() - 1;
            if (mOpen && continues(mSpans.get(last)[1], mRects.get(last), start, rect)) {
                float[] run = mRects.get(last);
                mSpans.get(last)[1] = end;
                run[0] = Math.min(run[0], rect[0]);
                run[1] = Math.min(run[1], rect[1]);
                run[2] = Math.max(run[2], rect[2]);
                run[3] = Math.max(run[3], rect[3]);
                return;
            }
            mSpans.add(new int[] {start, end});
            mRects.add(rect.clone());
            mOpen = true;
        }

        List<Run> finish() {
            List<Run> runs = new ArrayList<>(mSpans.size());
            for (int i = 0; i < mSpans.size(); i++) {
                int[] span = mSpans.get(i);
                runs.add(new Run(mText.substring(span[0], span[1]), mRects.get(i), separatorAfter(i)));
            }
            return runs;
        }

        private boolean continues(int runEnd, float[] run, int start, float[] rect) {
            if (breaksLine(mText, runEnd, start) || !sameRow(run, rect)) {
                return false;
            }
            float height = Math.max(run[3] - run[1], rect[3] - rect[1]);
            float gap = rect[0] - run[2];
            return gap >= -height / 2 && gap <= height;
        }

        private String separatorAfter(int i) {
            if (i + 1 >= mSpans.size()) {
                return "";
            }
            int end = mSpans.get(i)[1];
            int next = mSpans.get(i + 1)[0];
            if (breaksLine(mText, end, next) || !sameRow(mRects.get(i), mRects.get(i + 1))) {
                return "\n";
            }
            return next > end ? " " : "";
        }
    }
}
