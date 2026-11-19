package org.mobilewebview;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether any document of the page plays audible media. Each document (frame)
 * reports its own start and stop under its own id; a navigation forgets every
 * document of the old page.
 */
final class MediaPlaybackTracker {
    /** A page can mint ids at will: the oldest go beyond this. */
    static final int MAX_DOCUMENTS = 64;

    private final Set<String> mPlaying = new LinkedHashSet<>();

    /** Returns true when the page-wide state changed. */
    boolean report(String documentId, boolean playing) {
        final boolean before = isPlaying();
        if (playing) {
            mPlaying.add(documentId);
            if (mPlaying.size() > MAX_DOCUMENTS) {
                final Iterator<String> oldest = mPlaying.iterator();
                oldest.next();
                oldest.remove();
            }
        } else {
            mPlaying.remove(documentId);
        }
        return before != isPlaying();
    }

    /** Returns true when the page was playing. */
    boolean reset() {
        final boolean before = isPlaying();
        mPlaying.clear();
        return before;
    }

    int playingCount() {
        return mPlaying.size();
    }

    boolean isPlaying() {
        return !mPlaying.isEmpty();
    }
}
