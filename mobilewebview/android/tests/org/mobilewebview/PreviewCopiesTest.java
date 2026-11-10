package org.mobilewebview;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.mobilewebview.TestAssert.assertEquals;
import static org.mobilewebview.TestAssert.assertFalse;
import static org.mobilewebview.TestAssert.assertTrue;

public final class PreviewCopiesTest {
    public static void main(String[] args) throws IOException {
        shouldCopyTheHeadThenTheRest();
        shouldRefuseAStreamOverTheLimit();
        shouldSweepWhatAnEarlierRunLeftOnce();
        System.out.println("PreviewCopiesTest passed");
    }

    private static void shouldCopyTheHeadThenTheRest() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PreviewCopies.copy(new byte[] {'%', 'P', 'D'}, 2, new ByteArrayInputStream(new byte[] {'X', 'Y'}),
                out, 10);
        assertEquals("%PXY", out.toString("US-ASCII"));
    }

    private static void shouldRefuseAStreamOverTheLimit() {
        boolean refused = false;
        try {
            PreviewCopies.copy(new byte[] {1, 2}, 2, new ByteArrayInputStream(new byte[9]),
                    new ByteArrayOutputStream(), 10);
        } catch (IOException e) {
            refused = true;
        }
        assertTrue(refused);
    }

    // Copies outlive a process Android kills; the next one starts clean.
    private static void shouldSweepWhatAnEarlierRunLeftOnce() throws IOException {
        File cache = Files.createTempDirectory("previews").toFile();
        PreviewCopies copies = new PreviewCopies();
        File dir = new File(cache, PreviewCopies.DIR);
        assertTrue(dir.mkdirs());
        File stale = new File(dir, "preview-old.pdf");
        assertTrue(stale.createNewFile());

        File first = copies.create(cache);
        assertFalse(stale.exists());
        assertTrue(first.exists());
        File second = copies.create(cache);
        assertTrue(first.exists());
        assertTrue(second.exists());
    }
}
