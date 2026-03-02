package org.mobilewebview;

final class BridgeScriptBuilder {
    private BridgeScriptBuilder() {
    }

    static String buildDeliverScript(String namespace, String jsonMessage) {
        String namespaceJsLiteral = quoteJsLiteral(namespace != null ? namespace : "");
        String messageJsLiteral = quoteJsLiteral(jsonMessage != null ? jsonMessage : "");

        return String.format(
            "(function(ns, msg) {" +
            "  var t = window[ns] && window[ns].__deliverMessage;" +
            "  if (typeof t === 'function') {" +
            "    try { t(msg); return 'ok'; }" +
            "    catch (e) { console.error('[QtBridge] __deliverMessage error:', e); return 'error: ' + e.message; }" +
            "  } else {" +
            "    console.warn('[QtBridge] No __deliverMessage function');" +
            "    return 'no_transport';" +
            "  }" +
            "})(%s, %s);",
            namespaceJsLiteral, messageJsLiteral
        );
    }

    static String quoteJsLiteral(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
        return out.toString();
    }
}
