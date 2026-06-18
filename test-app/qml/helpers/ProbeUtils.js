.pragma library

function toJsLiteral(value) {
    return JSON.stringify(String(value))
}

function normalizeUrl(rawText) {
    var text = (rawText || "").trim()
    if (text.length === 0)
        return ""
    if (text.indexOf("://") === -1)
        text = "https://" + text
    return text
}

function writeLocalStorageScript(key, value) {
    return "(function(){"
        + "localStorage.setItem(" + toJsLiteral(key) + ", " + toJsLiteral(value) + ");"
        + "return JSON.stringify({ls: localStorage.getItem(" + toJsLiteral(key) + ")});"
        + "})()"
}

function readLocalStorageScript(key) {
    return "(function(){"
        + "return JSON.stringify({ls: localStorage.getItem(" + toJsLiteral(key) + ")});"
        + "})()"
}

function writeCookieScript(name, value, maxAgeSec) {
    var age = parseInt(maxAgeSec, 10)
    if (isNaN(age) || age < 0)
        age = 0

    return "(function(){"
        + "document.cookie=" + toJsLiteral(name) + "+'='+" + toJsLiteral(value)
        + "+'; path=/; max-age='+" + age + ";"
        + "return JSON.stringify({cookieWritten: " + toJsLiteral(name) + "});"
        + "})()"
}

function readCookieScript(name) {
    return "(function(){"
        + "var cName=" + toJsLiteral(name) + ";"
        + "function cookieValue(n){"
        + "  var parts=document.cookie.split('; ');"
        + "  for(var i=0;i<parts.length;i++){"
        + "    if(parts[i].indexOf(n+'=')===0) return parts[i].substring(n.length+1);"
        + "  }"
        + "  return null;"
        + "}"
        + "return JSON.stringify({cookie: cookieValue(cName)});"
        + "})()"
}

function isolationProbeScript() {
    return "(function(){"
        + "window.__pageVar='PAGE';"
        + "return JSON.stringify({"
        + "  pageSeesUserscriptVar: typeof window.__userscriptVar,"
        + "  userscriptDomMarker: document.documentElement.getAttribute('data-userscript-var'),"
        + "  userscriptSeesPage: document.documentElement.getAttribute('data-userscript-sees-page')"
        + "});"
        + "})()"
}

function loadStorageTestPageScript(storageTestPageHtml, baseUrl) {
    if (typeof storageTestPageHtml === "string" && storageTestPageHtml.length > 0)
        return { html: storageTestPageHtml, baseUrl: baseUrl }
    return null
}

function parseProbeValue(result) {
    if (result === null || result === undefined)
        return null
    var text = String(result)
    if (text.length === 0 || text === "null" || text === "undefined")
        return null
    try {
        return JSON.parse(text)
    } catch (e) {
        return { raw: text }
    }
}

function compareIsolation(writeValue, readValue) {
    if (writeValue === null || writeValue === undefined || writeValue === "")
        return "unknown"
    if (readValue === writeValue)
        return "shared"
    return "isolated"
}
