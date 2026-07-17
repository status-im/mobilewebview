#pragma once

#include <QObject>
#include <QJsonObject>
#include <QVariant>

class QHttpServer;
class QQuickWindow;

/// Localhost HTTP control plane for agent / MCP automation of the test harness.
///
/// Endpoints:
///   GET  /health
///   GET  /state
///   POST /rpc   body: { "method": "...", "params": { ... } }
///
/// Bind: 127.0.0.1, port from MWV_AGENT_PORT (default 17321). Set MWV_AGENT_PORT=0 to disable.
class AgentControl : public QObject
{
    Q_OBJECT

public:
    explicit AgentControl(QObject *parent = nullptr);
    ~AgentControl() override;

    void setRootWindow(QObject *root);
    bool start();
    quint16 port() const { return m_port; }

private:
    QJsonObject handleRpc(const QString &method, const QJsonObject &params);
    QJsonObject jsonOk(const QJsonObject &result = {}) const;
    QJsonObject jsonErr(const QString &message) const;

    QVariant callQml(const char *method,
                     const QVariant &a1 = {},
                     const QVariant &a2 = {},
                     const QVariant &a3 = {}) const;
    QObject *webViewObject() const;
    QQuickWindow *quickWindow() const;

    QJsonObject rpcListScreens();
    QJsonObject rpcOpenScreen(const QJsonObject &params);
    QJsonObject rpcGoHome();
    QJsonObject rpcPressBack();
    QJsonObject rpcState();
    QJsonObject rpcNavigate(const QJsonObject &params);
    QJsonObject rpcLoadHtml(const QJsonObject &params);
    QJsonObject rpcGoBack();
    QJsonObject rpcGoForward();
    QJsonObject rpcReload(const QJsonObject &params);
    QJsonObject rpcStop();
    QJsonObject rpcEvalJs(const QJsonObject &params);
    QJsonObject rpcFindText(const QJsonObject &params);
    QJsonObject rpcStopFind();
    QJsonObject rpcSetZoom(const QJsonObject &params);
    QJsonObject rpcInvoke(const QJsonObject &params);
    QJsonObject rpcSetProp(const QJsonObject &params);
    QJsonObject rpcWaitLoaded(const QJsonObject &params);
    QJsonObject rpcScreenshot(const QJsonObject &params);

    QHttpServer *m_server = nullptr;
    QObject *m_root = nullptr;
    quint16 m_port = 0;
};
