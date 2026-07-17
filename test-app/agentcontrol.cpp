#include "agentcontrol.h"

#include "MobileWebView/mobilewebviewbackend.h"

#include <QDateTime>
#include <QDir>
#include <QEventLoop>
#include <QHostAddress>
#include <QImage>
#include <QHttpServer>
#include <QHttpServerRequest>
#include <QHttpServerResponse>
#include <QJsonArray>
#include <QJsonDocument>
#include <QMetaObject>
#include <QQuickWindow>
#include <QTcpServer>
#include <QTimer>
#include <QUrl>

namespace {

quint16 portFromEnv()
{
    const QByteArray raw = qgetenv("MWV_AGENT_PORT");
    if (raw.isEmpty())
        return 17321;
    bool ok = false;
    const int value = raw.toInt(&ok);
    if (!ok || value < 0 || value > 65535)
        return 17321;
    return static_cast<quint16>(value);
}

QHttpServerResponse jsonResponse(const QJsonObject &obj, int status = 200)
{
    return QHttpServerResponse(obj, static_cast<QHttpServerResponse::StatusCode>(status));
}

} // namespace

AgentControl::AgentControl(QObject *parent)
    : QObject(parent)
    , m_server(new QHttpServer(this))
{
}

AgentControl::~AgentControl() = default;

void AgentControl::setRootWindow(QObject *root)
{
    m_root = root;
}

bool AgentControl::start()
{
    m_port = portFromEnv();
    if (m_port == 0) {
        qInfo("AgentControl: disabled (MWV_AGENT_PORT=0)");
        return false;
    }

    m_server->route(QStringLiteral("/health"), QHttpServerRequest::Method::Get,
                    [this]() {
                        return jsonResponse(QJsonObject{
                            {QStringLiteral("ok"), true},
                            {QStringLiteral("service"), QStringLiteral("mobilewebview-test-app")},
                            {QStringLiteral("port"), static_cast<int>(m_port)},
                        });
                    });

    m_server->route(QStringLiteral("/state"), QHttpServerRequest::Method::Get,
                    [this]() {
                        return jsonResponse(rpcState());
                    });

    m_server->route(QStringLiteral("/rpc"), QHttpServerRequest::Method::Post,
                    [this](const QHttpServerRequest &request) {
                        const QJsonDocument doc = QJsonDocument::fromJson(request.body());
                        if (!doc.isObject())
                            return jsonResponse(jsonErr(QStringLiteral("body must be a JSON object")), 400);

                        const QJsonObject body = doc.object();
                        const QString method = body.value(QStringLiteral("method")).toString();
                        if (method.isEmpty())
                            return jsonResponse(jsonErr(QStringLiteral("missing method")), 400);

                        const QJsonObject params = body.value(QStringLiteral("params")).toObject();
                        return jsonResponse(handleRpc(method, params));
                    });

    auto *tcp = new QTcpServer(m_server);
    if (!tcp->listen(QHostAddress::LocalHost, m_port)) {
        qWarning("AgentControl: failed to bind 127.0.0.1:%u — %s",
                 m_port, qPrintable(tcp->errorString()));
        delete tcp;
        m_port = 0;
        return false;
    }
    if (!m_server->bind(tcp)) {
        qWarning("AgentControl: QHttpServer::bind failed on port %u", m_port);
        m_port = 0;
        return false;
    }

    qInfo("AgentControl: listening on http://127.0.0.1:%u", m_port);
    return true;
}

QJsonObject AgentControl::jsonOk(const QJsonObject &result) const
{
    QJsonObject out{{QStringLiteral("ok"), true}};
    if (!result.isEmpty())
        out.insert(QStringLiteral("result"), result);
    return out;
}

QJsonObject AgentControl::jsonErr(const QString &message) const
{
    return QJsonObject{
        {QStringLiteral("ok"), false},
        {QStringLiteral("error"), message},
    };
}

QVariant AgentControl::callQml(const char *method,
                               const QVariant &a1,
                               const QVariant &a2,
                               const QVariant &a3) const
{
    if (!m_root)
        return {};

    QVariant result;
    bool invoked = false;
    if (!a1.isValid()) {
        invoked = QMetaObject::invokeMethod(m_root, method, Qt::DirectConnection,
                                            Q_RETURN_ARG(QVariant, result));
    } else if (!a2.isValid()) {
        invoked = QMetaObject::invokeMethod(m_root, method, Qt::DirectConnection,
                                            Q_RETURN_ARG(QVariant, result),
                                            Q_ARG(QVariant, a1));
    } else if (!a3.isValid()) {
        invoked = QMetaObject::invokeMethod(m_root, method, Qt::DirectConnection,
                                            Q_RETURN_ARG(QVariant, result),
                                            Q_ARG(QVariant, a1),
                                            Q_ARG(QVariant, a2));
    } else {
        invoked = QMetaObject::invokeMethod(m_root, method, Qt::DirectConnection,
                                            Q_RETURN_ARG(QVariant, result),
                                            Q_ARG(QVariant, a1),
                                            Q_ARG(QVariant, a2),
                                            Q_ARG(QVariant, a3));
    }
    if (!invoked)
        return QVariant();
    return result;
}

QObject *AgentControl::webViewObject() const
{
    const QVariant v = callQml("agentWebView");
    return qvariant_cast<QObject *>(v);
}

QQuickWindow *AgentControl::quickWindow() const
{
    return qobject_cast<QQuickWindow *>(m_root);
}

QJsonObject AgentControl::handleRpc(const QString &method, const QJsonObject &params)
{
    if (!m_root)
        return jsonErr(QStringLiteral("root window not ready"));

    if (method == QLatin1String("list_screens"))
        return rpcListScreens();
    if (method == QLatin1String("open_screen"))
        return rpcOpenScreen(params);
    if (method == QLatin1String("go_home"))
        return rpcGoHome();
    if (method == QLatin1String("press_back"))
        return rpcPressBack();
    if (method == QLatin1String("state"))
        return rpcState();
    if (method == QLatin1String("navigate"))
        return rpcNavigate(params);
    if (method == QLatin1String("load_html"))
        return rpcLoadHtml(params);
    if (method == QLatin1String("go_back"))
        return rpcGoBack();
    if (method == QLatin1String("go_forward"))
        return rpcGoForward();
    if (method == QLatin1String("reload"))
        return rpcReload(params);
    if (method == QLatin1String("stop"))
        return rpcStop();
    if (method == QLatin1String("eval_js"))
        return rpcEvalJs(params);
    if (method == QLatin1String("find_text"))
        return rpcFindText(params);
    if (method == QLatin1String("stop_find"))
        return rpcStopFind();
    if (method == QLatin1String("set_zoom"))
        return rpcSetZoom(params);
    if (method == QLatin1String("invoke"))
        return rpcInvoke(params);
    if (method == QLatin1String("set_prop"))
        return rpcSetProp(params);
    if (method == QLatin1String("wait_loaded"))
        return rpcWaitLoaded(params);
    if (method == QLatin1String("screenshot"))
        return rpcScreenshot(params);

    return jsonErr(QStringLiteral("unknown method: ") + method);
}

QJsonObject AgentControl::rpcListScreens()
{
    const QVariant v = callQml("agentListScreens");
    const QJsonArray arr = QJsonArray::fromVariantList(v.toList());
    return jsonOk({{QStringLiteral("screens"), arr}});
}

QJsonObject AgentControl::rpcOpenScreen(const QJsonObject &params)
{
    const QString id = params.value(QStringLiteral("id")).toString();
    if (id.isEmpty())
        return jsonErr(QStringLiteral("params.id required"));
    const QVariant v = callQml("agentOpenScreen", id);
    const QVariantMap map = v.toMap();
    if (!map.value(QStringLiteral("ok")).toBool())
        return jsonErr(map.value(QStringLiteral("error")).toString());
    return jsonOk(QJsonObject::fromVariantMap(map));
}

QJsonObject AgentControl::rpcGoHome()
{
    callQml("agentGoHome");
    return jsonOk();
}

QJsonObject AgentControl::rpcPressBack()
{
    const QVariant v = callQml("agentPressBack");
    const QVariantMap map = v.toMap();
    if (!map.value(QStringLiteral("ok"), true).toBool())
        return jsonErr(map.value(QStringLiteral("error")).toString());
    return jsonOk(QJsonObject::fromVariantMap(map));
}

QJsonObject AgentControl::rpcState()
{
    const QVariant v = callQml("agentState");
    return jsonOk(QJsonObject::fromVariantMap(v.toMap()));
}

QJsonObject AgentControl::rpcNavigate(const QJsonObject &params)
{
    const QString url = params.value(QStringLiteral("url")).toString();
    if (url.isEmpty())
        return jsonErr(QStringLiteral("params.url required"));
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    wv->loadUrl(QUrl(url));
    return jsonOk({{QStringLiteral("url"), url}});
}

QJsonObject AgentControl::rpcLoadHtml(const QJsonObject &params)
{
    const QString html = params.value(QStringLiteral("html")).toString();
    if (html.isEmpty())
        return jsonErr(QStringLiteral("params.html required"));
    const QString base = params.value(QStringLiteral("baseUrl")).toString(QStringLiteral("about:blank"));
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    wv->loadHtml(html, QUrl(base));
    return jsonOk();
}

QJsonObject AgentControl::rpcGoBack()
{
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    wv->goBack();
    return jsonOk();
}

QJsonObject AgentControl::rpcGoForward()
{
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    wv->goForward();
    return jsonOk();
}

QJsonObject AgentControl::rpcReload(const QJsonObject &params)
{
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    if (params.value(QStringLiteral("bypassCache")).toBool())
        wv->reloadAndBypassCache();
    else
        wv->reload();
    return jsonOk();
}

QJsonObject AgentControl::rpcStop()
{
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    wv->stop();
    return jsonOk();
}

QJsonObject AgentControl::rpcEvalJs(const QJsonObject &params)
{
    const QString script = params.value(QStringLiteral("script")).toString();
    if (script.isEmpty())
        return jsonErr(QStringLiteral("params.script required"));
    const int timeoutMs = params.value(QStringLiteral("timeoutMs")).toInt(10000);

    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));

    QEventLoop loop;
    QVariant result;
    QString error;
    bool got = false;

    const auto conn = QObject::connect(
        wv, &MobileWebViewBackend::javaScriptResult,
        &loop, [&](const QVariant &r, const QString &e) {
            result = r;
            error = e;
            got = true;
            loop.quit();
        });

    QTimer timer;
    timer.setSingleShot(true);
    QObject::connect(&timer, &QTimer::timeout, &loop, &QEventLoop::quit);
    timer.start(timeoutMs);

    wv->runJavaScript(script);
    loop.exec();
    QObject::disconnect(conn);

    if (!got)
        return jsonErr(QStringLiteral("eval_js timed out after %1 ms").arg(timeoutMs));
    if (!error.isEmpty())
        return jsonErr(error);

    return jsonOk({{QStringLiteral("value"), QJsonValue::fromVariant(result)}});
}

QJsonObject AgentControl::rpcFindText(const QJsonObject &params)
{
    const QString text = params.value(QStringLiteral("text")).toString();
    if (text.isEmpty())
        return jsonErr(QStringLiteral("params.text required"));
    const int flags = params.value(QStringLiteral("flags")).toInt(0);
    const int timeoutMs = params.value(QStringLiteral("timeoutMs")).toInt(5000);

    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));

    QEventLoop loop;
    int active = -1;
    int count = 0;
    bool got = false;
    const auto conn = QObject::connect(
        wv, &MobileWebViewBackend::findTextResult,
        &loop, [&](int a, int c) {
            active = a;
            count = c;
            got = true;
            loop.quit();
        });

    QTimer timer;
    timer.setSingleShot(true);
    QObject::connect(&timer, &QTimer::timeout, &loop, &QEventLoop::quit);
    timer.start(timeoutMs);

    wv->findText(text, flags);
    loop.exec();
    QObject::disconnect(conn);

    if (!got)
        return jsonErr(QStringLiteral("find_text timed out"));

    return jsonOk({
        {QStringLiteral("activeMatchIndex"), active},
        {QStringLiteral("matchCount"), count},
    });
}

QJsonObject AgentControl::rpcStopFind()
{
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    wv->stopFind();
    return jsonOk();
}

QJsonObject AgentControl::rpcSetZoom(const QJsonObject &params)
{
    if (!params.contains(QStringLiteral("factor")))
        return jsonErr(QStringLiteral("params.factor required"));
    const qreal factor = params.value(QStringLiteral("factor")).toDouble();
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));
    wv->setZoomFactor(factor);
    return jsonOk({{QStringLiteral("factor"), wv->zoomFactor()}});
}

QJsonObject AgentControl::rpcInvoke(const QJsonObject &params)
{
    const QString action = params.value(QStringLiteral("action")).toString();
    if (action.isEmpty())
        return jsonErr(QStringLiteral("params.action required"));
    const QVariant v = callQml("agentInvoke", action);
    const QVariantMap map = v.toMap();
    if (!map.value(QStringLiteral("ok")).toBool())
        return jsonErr(map.value(QStringLiteral("error")).toString());
    return jsonOk(QJsonObject::fromVariantMap(map));
}

QJsonObject AgentControl::rpcSetProp(const QJsonObject &params)
{
    const QString name = params.value(QStringLiteral("name")).toString();
    if (name.isEmpty())
        return jsonErr(QStringLiteral("params.name required"));
    if (!params.contains(QStringLiteral("value")))
        return jsonErr(QStringLiteral("params.value required"));
    const QVariant value = params.value(QStringLiteral("value")).toVariant();
    const QVariant v = callQml("agentSetProp", name, value);
    const QVariantMap map = v.toMap();
    if (!map.value(QStringLiteral("ok")).toBool())
        return jsonErr(map.value(QStringLiteral("error")).toString());
    return jsonOk(QJsonObject::fromVariantMap(map));
}

QJsonObject AgentControl::rpcWaitLoaded(const QJsonObject &params)
{
    const int timeoutMs = params.value(QStringLiteral("timeoutMs")).toInt(15000);
    auto *wv = qobject_cast<MobileWebViewBackend *>(webViewObject());
    if (!wv)
        return jsonErr(QStringLiteral("no webView on current screen"));

    if (wv->loaded() && !wv->loading()) {
        return jsonOk({
            {QStringLiteral("url"), wv->url().toString()},
            {QStringLiteral("title"), wv->title()},
            {QStringLiteral("waited"), false},
        });
    }

    QEventLoop loop;
    bool done = false;
    const auto check = [&]() {
        if (wv->loaded() && !wv->loading()) {
            done = true;
            loop.quit();
        }
    };
    const auto c1 = QObject::connect(wv, &MobileWebViewBackend::loadedChanged, &loop, check);
    const auto c2 = QObject::connect(wv, &MobileWebViewBackend::loadingChanged, &loop, check);

    QTimer timer;
    timer.setSingleShot(true);
    QObject::connect(&timer, &QTimer::timeout, &loop, &QEventLoop::quit);
    timer.start(timeoutMs);

    check();
    if (!done)
        loop.exec();

    QObject::disconnect(c1);
    QObject::disconnect(c2);

    if (!(wv->loaded() && !wv->loading()))
        return jsonErr(QStringLiteral("wait_loaded timed out"));

    return jsonOk({
        {QStringLiteral("url"), wv->url().toString()},
        {QStringLiteral("title"), wv->title()},
        {QStringLiteral("waited"), true},
    });
}

QJsonObject AgentControl::rpcScreenshot(const QJsonObject &params)
{
    auto *window = quickWindow();
    if (!window)
        return jsonErr(QStringLiteral("no QQuickWindow"));

    QString path = params.value(QStringLiteral("path")).toString();
    if (path.isEmpty()) {
        path = QDir::temp().filePath(
            QStringLiteral("mwv-test-app-%1.png")
                .arg(QDateTime::currentMSecsSinceEpoch()));
    }

    const QImage image = window->grabWindow();
    if (image.isNull())
        return jsonErr(QStringLiteral("grabWindow returned null"));
    if (!image.save(path))
        return jsonErr(QStringLiteral("failed to save screenshot to ") + path);

    return jsonOk({
        {QStringLiteral("path"), path},
        {QStringLiteral("width"), image.width()},
        {QStringLiteral("height"), image.height()},
    });
}
