package org.mobilewebview;

public final class RendererRecoveryTest {
    public static void main(String[] args) {
        nothingToReviveWhileTheRendererLives();
        aGoneRendererRevivesOnce();
        reviveRestoresTheSavedHistory();
        reviveLoadsTheLastUrlWithoutHistory();
        reviveIsBlankWithNothingToShow();
        clearedHistoryIsNotRestored();
        emptyStateKeepsThePreviousOne();
        aNewerUrlWinsOverOlderHistory();
        historySavedAfterTheUrlStillWins();
        htmlContentRevivesAsHtml();
        navigatingAwayFromHtmlForgetsIt();
        aDestroyedViewNeverRevives();
        onlyAKillUnderAShownViewAutoRevives();
        autoReviveHappensOncePerUserAction();
        aRevivedViewIsVisibleOnlyWhenShown();
        aUserReviveCancelsAScheduledAutoRevive();
        aScheduledAutoReviveRunsOnce();
        htmlWinsOverHistoryWhileStillOnTheHtmlDoc();
        System.out.println("RendererRecoveryTest passed");
    }

    private static void nothingToReviveWhileTheRendererLives() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://status.app/");

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.NOT_GONE);
    }

    private static void aGoneRendererRevivesOnce() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://status.app/");
        recovery.onGone();

        TestAssert.assertTrue(recovery.isGone());
        TestAssert.assertTrue(recovery.takeRevive() != RendererRecovery.Revive.NOT_GONE);
        TestAssert.assertFalse(recovery.isGone());
        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.NOT_GONE);
    }

    private static void reviveRestoresTheSavedHistory() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://status.app/");
        recovery.onStateSaved(new byte[] { 1, 2, 3 });
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.RESTORE_STATE);
        TestAssert.assertEquals(3, recovery.state().length);
    }

    private static void reviveLoadsTheLastUrlWithoutHistory() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://status.app/");
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.LOAD_URL);
        TestAssert.assertEquals("https://status.app/", recovery.url());
    }

    private static void reviveIsBlankWithNothingToShow() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.BLANK);
    }

    private static void clearedHistoryIsNotRestored() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://status.app/");
        recovery.onStateSaved(new byte[] { 1 });
        recovery.clearState();
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.LOAD_URL);
    }

    private static void emptyStateKeepsThePreviousOne() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onStateSaved(new byte[] { 1 });
        recovery.onStateSaved(new byte[0]);
        recovery.onStateSaved(null);
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.RESTORE_STATE);
    }

    // History captured before the last navigation would take the user back a page.
    private static void aNewerUrlWinsOverOlderHistory() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://a.example/");
        recovery.onStateSaved(new byte[] { 1 });
        recovery.onUrl("https://b.example/");
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.LOAD_URL);
        TestAssert.assertEquals("https://b.example/", recovery.url());
    }

    private static void historySavedAfterTheUrlStillWins() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://a.example/");
        recovery.onUrl("https://b.example/");
        recovery.onStateSaved(new byte[] { 1 });
        recovery.onUrl("https://b.example/");
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.RESTORE_STATE);
    }

    private static void htmlContentRevivesAsHtml() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onHtml("<p>hi</p>", "https://base.example/");
        recovery.onUrl("https://base.example/");
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.LOAD_HTML);
        TestAssert.assertEquals("<p>hi</p>", recovery.html());
        TestAssert.assertEquals("https://base.example/", recovery.htmlBaseUrl());
    }

    private static void navigatingAwayFromHtmlForgetsIt() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onHtml("<p>hi</p>", "https://base.example/");
        recovery.onUrl("https://elsewhere.example/");
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.LOAD_URL);
    }

    // A revive posted behind destroy() must not bring a view back.
    private static void aDestroyedViewNeverRevives() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://status.app/");
        recovery.onGone();
        recovery.onDestroyed();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.NOT_GONE);
        TestAssert.assertFalse(recovery.shouldAutoRevive(false, true));
    }

    private static void onlyAKillUnderAShownViewAutoRevives() {
        TestAssert.assertTrue(new RendererRecovery().shouldAutoRevive(false, true));
        TestAssert.assertFalse("a crash waits for the host", new RendererRecovery().shouldAutoRevive(true, true));
        TestAssert.assertFalse("hidden revives when shown", new RendererRecovery().shouldAutoRevive(false, false));
    }

    // Kill, revive, kill again: no reload loop without the user.
    private static void autoReviveHappensOncePerUserAction() {
        RendererRecovery recovery = new RendererRecovery();
        TestAssert.assertTrue(recovery.shouldAutoRevive(false, true));
        recovery.onAutoRevived();

        TestAssert.assertFalse(recovery.shouldAutoRevive(false, true));

        recovery.onUserAction();
        TestAssert.assertTrue(recovery.shouldAutoRevive(false, true));
    }

    // A focus listener left from an earlier loss must not revive behind the user's back.
    private static void aUserReviveCancelsAScheduledAutoRevive() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onUrl("https://status.app/");
        recovery.onGone();
        recovery.scheduleAutoRevive();

        recovery.takeRevive();
        recovery.onGone();

        TestAssert.assertFalse(recovery.takeScheduledAutoRevive());
    }

    private static void aScheduledAutoReviveRunsOnce() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onGone();
        recovery.scheduleAutoRevive();

        TestAssert.assertTrue(recovery.takeScheduledAutoRevive());
        TestAssert.assertFalse(recovery.takeScheduledAutoRevive());
    }

    // History of a loadHtml() page holds its base URL, not the content.
    private static void htmlWinsOverHistoryWhileStillOnTheHtmlDoc() {
        RendererRecovery recovery = new RendererRecovery();
        recovery.onHtml("<p>hi</p>", "https://base.example/");
        recovery.onUrl("https://base.example/");
        recovery.onStateSaved(new byte[] { 1 });
        recovery.onGone();

        TestAssert.assertTrue(recovery.takeRevive() == RendererRecovery.Revive.LOAD_HTML);
    }

    private static void aRevivedViewIsVisibleOnlyWhenShown() {
        TestAssert.assertTrue(RendererRecovery.visibleAfterRevive(true));
        TestAssert.assertFalse(RendererRecovery.visibleAfterRevive(false));
    }
}
