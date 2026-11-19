package org.mobilewebview;

public final class MediaPlaybackTrackerTest {
    public static void main(String[] args) {
        firstPlayingDocumentTurnsPlaybackOn();
        playbackStaysOnWhileAnyDocumentStillPlays();
        aRepeatedReportCountsOnce();
        aStrayStopChangesNothing();
        navigationResetsPlayback();
        documentIdsAreCapped();
        System.out.println("MediaPlaybackTrackerTest passed");
    }

    private static void firstPlayingDocumentTurnsPlaybackOn() {
        MediaPlaybackTracker tracker = new MediaPlaybackTracker();

        TestAssert.assertTrue(tracker.report("a", true));
        TestAssert.assertTrue(tracker.isPlaying());
        TestAssert.assertTrue(tracker.report("a", false));
        TestAssert.assertFalse(tracker.isPlaying());
    }

    private static void playbackStaysOnWhileAnyDocumentStillPlays() {
        MediaPlaybackTracker tracker = new MediaPlaybackTracker();

        tracker.report("a", true);
        TestAssert.assertFalse(tracker.report("b", true));
        TestAssert.assertFalse(tracker.report("a", false));
        TestAssert.assertTrue(tracker.isPlaying());
        TestAssert.assertTrue(tracker.report("b", false));
    }

    private static void aRepeatedReportCountsOnce() {
        MediaPlaybackTracker tracker = new MediaPlaybackTracker();

        tracker.report("a", true);
        tracker.report("a", true);

        TestAssert.assertTrue(tracker.report("a", false));
        TestAssert.assertFalse(tracker.isPlaying());
    }

    // A document of the previous page may still report after the navigation reset.
    private static void aStrayStopChangesNothing() {
        MediaPlaybackTracker tracker = new MediaPlaybackTracker();

        TestAssert.assertFalse(tracker.report("old", false));
        TestAssert.assertFalse(tracker.isPlaying());
    }

    // A page can mint ids at will; the set never grows past the cap.
    private static void documentIdsAreCapped() {
        MediaPlaybackTracker tracker = new MediaPlaybackTracker();
        for (int i = 0; i < MediaPlaybackTracker.MAX_DOCUMENTS + 10; ++i) {
            tracker.report("d" + i, true);
        }

        TestAssert.assertEquals(MediaPlaybackTracker.MAX_DOCUMENTS, tracker.playingCount());
        TestAssert.assertTrue(tracker.isPlaying());
    }

    private static void navigationResetsPlayback() {
        MediaPlaybackTracker tracker = new MediaPlaybackTracker();
        tracker.report("a", true);
        tracker.report("b", true);

        TestAssert.assertTrue(tracker.reset());
        TestAssert.assertFalse(tracker.isPlaying());
        TestAssert.assertFalse(tracker.reset());
    }
}
