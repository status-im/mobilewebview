#include "webchanneltransport.h"
#include "origin_utils.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QDebug>

WebChannelTransport::WebChannelTransport(QObject *parent)
    : QWebChannelAbstractTransport(parent)
{
}

void WebChannelTransport::sendMessage(const QJsonObject &message)
{
    const QString json = QString::fromUtf8(QJsonDocument(message).toJson(QJsonDocument::Compact));
    emit sendMessageRequested(json);
}

void WebChannelTransport::setAllowedOrigins(const QStringList &origins)
{
    m_allowedOrigins = origins;
}

void WebChannelTransport::setInvokeKey(const QString &key)
{
    m_invokeKey = key;
}

void WebChannelTransport::handleJsEnvelope(const QString &envelopeJson,
                                           const QString &reportedOrigin,
                                           bool /*isMainFrame*/)
{
    // Envelope format: { "invokeKey": "<key>", "data": "<qwebchannel JSON string>" }
    const QJsonDocument doc = QJsonDocument::fromJson(envelopeJson.toUtf8());
    if (doc.isNull() || !doc.isObject()) {
        qWarning() << "WebChannelTransport: Invalid envelope JSON";
        return;
    }

    const QJsonObject obj = doc.object();
    const QString key = obj.value(QLatin1String("invokeKey")).toString();
    const QString data = obj.value(QLatin1String("data")).toString();

    // Validate invoke key to prevent stale messages from previous navigations
    if (!m_invokeKey.isEmpty() && key != m_invokeKey) {
        return;
    }

    // Validate origin using shared origin validation
    if (!m_allowedOrigins.isEmpty() && !isOriginAllowed(reportedOrigin, m_allowedOrigins)) {
        qWarning() << "WebChannelTransport: Ignoring message from disallowed origin:" << reportedOrigin;
        return;
    }

    // Parse the QWebChannel message
    const QJsonDocument payload = QJsonDocument::fromJson(data.toUtf8());
    if (!payload.isNull() && payload.isObject()) {
        emit messageReceived(payload.object(), this);
    } else {
        qWarning() << "WebChannelTransport: Failed to parse payload";
    }
}
