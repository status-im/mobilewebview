package org.mobilewebview;

import java.util.Arrays;
import java.util.List;

public final class ContentAuthoritiesTest {
    private static final List<String> PROVIDERS = Arrays.asList(
            "com.google.android.apps.nbu.files.provider",
            "org.qtproject.example.MobileWebViewTest.qtprovider",
            "com.example.first;com.Example.Second");

    public static void main(String[] args) {
        shouldRestoreTheProvidersSpellingOfALowercasedAuthority();
        shouldLeavePathQueryAndEscapesAlone();
        shouldMatchAnyAuthorityOfAProvider();
        shouldLeaveAnUnknownOrExactAuthorityAsItIs();
        shouldLeaveOtherSchemesAlone();
        shouldRefuseAnAuthorityTwoProvidersSpellDifferently();
        System.out.println("ContentAuthoritiesTest passed");
    }

    // QUrl lowercases the host; Android provider authorities are case-sensitive.
    private static void shouldRestoreTheProvidersSpellingOfALowercasedAuthority() {
        assertEquals("content://org.qtproject.example.MobileWebViewTest.qtprovider/files_path/a.txt",
                ContentAuthorities.restoreCase(
                        "content://org.qtproject.example.mobilewebviewtest.qtprovider/files_path/a.txt",
                        PROVIDERS));
    }

    private static void shouldLeavePathQueryAndEscapesAlone() {
        assertEquals("content://org.qtproject.example.MobileWebViewTest.qtprovider/Files%20Path/A.txt?x=%2F#f",
                ContentAuthorities.restoreCase(
                        "content://org.qtproject.example.mobilewebviewtest.qtprovider/Files%20Path/A.txt?x=%2F#f",
                        PROVIDERS));
        assertEquals("content://org.qtproject.example.MobileWebViewTest.qtprovider",
                ContentAuthorities.restoreCase(
                        "content://org.qtproject.example.mobilewebviewtest.qtprovider", PROVIDERS));
    }

    private static void shouldMatchAnyAuthorityOfAProvider() {
        assertEquals("content://com.Example.Second/1",
                ContentAuthorities.restoreCase("content://com.example.second/1", PROVIDERS));
    }

    private static void shouldLeaveAnUnknownOrExactAuthorityAsItIs() {
        String unknown = "content://com.unknown.provider/1";
        assertEquals(unknown, ContentAuthorities.restoreCase(unknown, PROVIDERS));
        String exact = "content://com.google.android.apps.nbu.files.provider/2/1000000110";
        assertEquals(exact, ContentAuthorities.restoreCase(exact, PROVIDERS));
    }

    private static void shouldLeaveOtherSchemesAlone() {
        String file = "file:///storage/emulated/0/Download/x.pdf";
        assertEquals(file, ContentAuthorities.restoreCase(file, PROVIDERS));
        assertEquals(null, ContentAuthorities.restoreCase(null, PROVIDERS));
    }

    // Another app can declare the lowercase spelling of a provider's authority;
    // a lowercased URI then names either provider, so it names none.
    private static void shouldRefuseAnAuthorityTwoProvidersSpellDifferently() {
        List<String> providers = Arrays.asList("com.Example.Docs", "com.example.docs");
        assertEquals(null, ContentAuthorities.restoreCase("content://com.example.docs/1", providers));
        // Spelled with capitals, the URI did not come through QUrl: it names its provider.
        assertEquals("content://com.Example.Docs/1",
                ContentAuthorities.restoreCase("content://com.Example.Docs/1", providers));
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
