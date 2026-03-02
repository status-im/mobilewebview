#pragma once

#include <QWebChannelAbstractTransport>
#include <QStringList>
#include <QJsonObject>

// Common WebChannel transport layer for mobile WebView backends
// Bridges native WebView message handlers with Qt's WebChannel system
// This class unifies the duplicate implementations from Android and Darwin
class WebChannelTransport : public QWebChannelAbstractTransport {
    Q_OBJECT
public:
    explicit WebChannelTransport(QObject *parent = nullptr);
    
    // Send a message from QWebChannel -> JavaScript
    void sendMessage(const QJsonObject &message) override;
    
    // Set allowed origins for security validation (e.g., ["https://example.com"])
    void setAllowedOrigins(const QStringList &origins);
    
    // Set the invoke key for validation (unique session key to prevent stale messages)
    void setInvokeKey(const QString &key);
    
public slots:
    // Handle incoming message from JavaScript
    void handleJsEnvelope(const QString &envelopeJson, 
                          const QString &reportedOrigin, 
                          bool isMainFrame);
    
signals:
    // Emitted when a message needs to be sent to JavaScript
    // The backend implementation should connect to this and forward to the native WebView
    void sendMessageRequested(const QString &json);
    
private:
    QString m_invokeKey;
    QStringList m_allowedOrigins;
};
