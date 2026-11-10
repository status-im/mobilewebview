#pragma once

#include <QObject>
#include <QString>
#include <QUrl>
#include <QVariantList>
#include <QVariantMap>

/// Receives files the OS hands the app through "Open with" and measures how
/// they can be read: in place, through a private copy, by the Web View.
/// Every receipt and probe result is appended to a JSONL log.
class OpenedFileIntake : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString platform READ platform CONSTANT)
    Q_PROPERTY(QString logPath READ logPath CONSTANT)

public:
    explicit OpenedFileIntake(QObject *parent = nullptr);
    ~OpenedFileIntake() override;

    QString platform() const;
    QString logPath() const;

    Q_INVOKABLE QVariantList receipts() const;
    Q_INVOKABLE QVariantMap describe(const QString &url) const;
    Q_INVOKABLE QVariantMap read(const QString &url) const;
    /// iOS: start security-scoped access on the URL rebuilt from its string,
    /// or on the NSURL object the scene delegate received.
    Q_INVOKABLE QVariantMap startAccess(const QString &url, bool nativeUrl) const;
    Q_INVOKABLE QVariantMap copyToCache(const QString &url) const;
    Q_INVOKABLE QVariantMap pdfPages(const QString &url) const;
    Q_INVOKABLE QVariantList inboxFiles() const;
    Q_INVOKABLE QVariantList nativeContexts() const;
    Q_INVOKABLE void log(const QVariantMap &record) const;
    Q_INVOKABLE void clearLog() const;

signals:
    void received(const QVariantMap &receipt);

public slots:
    /// QDesktopServices handler for the file scheme.
    void handleUrl(const QUrl &url);

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void accept(const QString &url, const QString &via, const QVariantMap &extra);
    void onNativeUrl(const QString &url);
    void drainIntents();
    bool hasReceiptFor(const QString &url) const;

    QVariantList m_receipts;
};
