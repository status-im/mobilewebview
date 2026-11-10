package org.mobilewebview;

import java.util.ArrayList;
import java.util.List;

public final class PdfTextLayerTest {
    public static void main(String[] args) {
        shouldJoinWordsThePageDrawsSideBySide();
        shouldBreakRunsAtLineBreaks();
        shouldSplitALineWhereThePageLeavesAGap();
        shouldSplitALineThatJumpsToAnotherRow();
        shouldPlaceAWordDrawnInPiecesCharacterByCharacter();
        shouldSkipTextThePageDoesNotDraw();
        shouldWriteRunsAsJson();
        System.out.println("PdfTextLayerTest passed");
    }

    private static void shouldJoinWordsThePageDrawsSideBySide() {
        FakePage page = new FakePage().word("The", 10, 20).text(" ").word("quick", 30, 20);
        List<PdfTextLayer.Run> runs = PdfTextLayer.runs(page.text(), page);
        assertEquals(1, runs.size());
        assertRun(runs.get(0), "The quick", 10, 20, 60, 30);
        assertEquals("", runs.get(0).separator);
    }

    private static void shouldBreakRunsAtLineBreaks() {
        // Even when the next line continues on the same row.
        FakePage page = new FakePage().word("one", 10, 20).text("\r\n").word("two", 30, 20);
        List<PdfTextLayer.Run> runs = PdfTextLayer.runs(page.text(), page);
        assertEquals(2, runs.size());
        assertRun(runs.get(0), "one", 10, 20, 28, 30);
        assertRun(runs.get(1), "two", 30, 20, 48, 30);
        assertEquals("\n", runs.get(0).separator);
        assertEquals("", runs.get(1).separator);
    }

    // pdfium reports "Invoice   ...   Date" (a row spread across the page, or
    // two cells of a grid) as one line drawn as one wide rectangle.
    private static void shouldSplitALineWhereThePageLeavesAGap() {
        FakePage page = new FakePage().word("Invoice", 10, 20).text(" ").word("Date", 300, 20);
        List<PdfTextLayer.Run> runs = PdfTextLayer.runs(page.text(), page);
        assertEquals(2, runs.size());
        assertRun(runs.get(0), "Invoice", 10, 20, 52, 30);
        assertRun(runs.get(1), "Date", 300, 20, 324, 30);
        // Copied text keeps the space between the two.
        assertEquals(" ", runs.get(0).separator);
    }

    // ...and carries on into the next row without a line break.
    private static void shouldSplitALineThatJumpsToAnotherRow() {
        FakePage page = new FakePage().word("Date", 300, 20).text(" ").word("Total", 10, 40);
        List<PdfTextLayer.Run> runs = PdfTextLayer.runs(page.text(), page);
        assertEquals(2, runs.size());
        assertRun(runs.get(0), "Date", 300, 20, 324, 30);
        assertRun(runs.get(1), "Total", 10, 40, 40, 50);
        // Copied text puts the next row on a line of its own.
        assertEquals("\n", runs.get(0).separator);
    }

    private static void shouldPlaceAWordDrawnInPiecesCharacterByCharacter() {
        // No space between the end of one row and the start of the next.
        FakePage page = new FakePage().word("EUR", 300, 20).word("Left", 10, 40);
        List<PdfTextLayer.Run> runs = PdfTextLayer.runs(page.text(), page);
        assertEquals(2, runs.size());
        assertRun(runs.get(0), "EUR", 300, 20, 318, 30);
        assertRun(runs.get(1), "Left", 10, 40, 34, 50);
        assertEquals("\n", runs.get(0).separator);
    }

    // A run holds only text the page draws, even between words drawn side by side.
    private static void shouldSkipTextThePageDoesNotDraw() {
        FakePage page = new FakePage().word("seen", 10, 20).text(" ghost ").word("too", 40, 20);
        List<PdfTextLayer.Run> runs = PdfTextLayer.runs(page.text(), page);
        assertEquals(2, runs.size());
        assertRun(runs.get(0), "seen", 10, 20, 34, 30);
        assertRun(runs.get(1), "too", 40, 20, 58, 30);
        assertEquals(0, PdfTextLayer.runs("", page).size());
    }

    private static void shouldWriteRunsAsJson() {
        FakePage page = new FakePage().word("a\"b\\<c>", 10.25f, 20.5f).text("\r\n").word("2", 0, 40);
        assertEquals("[[10.25,20.5,52.25,30.5,\"a\\u0022b\\u005c\\u003cc\\u003e\",\"\\u000a\"],"
                + "[0,40,6,50,\"2\"]]",
                PdfTextLayer.json(PdfTextLayer.runs(page.text(), page)));
        assertEquals("[]", PdfTextLayer.json(new ArrayList<>()));
    }

    private static void assertRun(PdfTextLayer.Run run, String text,
                                  float left, float top, float right, float bottom) {
        assertEquals(text, run.text);
        assertEquals(left, run.left);
        assertEquals(top, run.top);
        assertEquals(right, run.right);
        assertEquals(bottom, run.bottom);
    }

    /**
     * A page that draws each character as a 6x10 box, and, like pdfium,
     * reports a stretch of text as one rectangle per row it is drawn on.
     */
    private static final class FakePage implements PdfTextLayer.Bounds {
        private final StringBuilder mText = new StringBuilder();
        private final List<float[]> mBoxes = new ArrayList<>();

        FakePage word(String word, float left, float top) {
            for (int i = 0; i < word.length(); i++) {
                mText.append(word.charAt(i));
                mBoxes.add(new float[] {left + 6 * i, top, left + 6 * (i + 1), top + 10});
            }
            return this;
        }

        // Text the page holds but draws nowhere: spaces, line breaks, hidden words.
        FakePage text(String text) {
            mText.append(text);
            for (int i = 0; i < text.length(); i++) {
                mBoxes.add(null);
            }
            return this;
        }

        String text() {
            return mText.toString();
        }

        @Override
        public List<float[]> of(int start, int end) {
            List<float[]> rects = new ArrayList<>();
            for (int i = start; i < end; i++) {
                float[] box = mBoxes.get(i);
                if (box == null) {
                    continue;
                }
                float[] last = rects.isEmpty() ? null : rects.get(rects.size() - 1);
                if (last != null && last[1] == box[1]) {
                    last[0] = Math.min(last[0], box[0]);
                    last[2] = Math.max(last[2], box[2]);
                } else {
                    rects.add(box.clone());
                }
            }
            return rects;
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
