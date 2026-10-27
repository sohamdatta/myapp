package com.example.loginapp.core;

import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** Sends through an SMTP server. The settings come from the environment, never the database: the password must not sit in a table. */
public final class SmtpMailSender implements MailSender {

    private final String host;
    private final String from;
    private final Session session;

    /**
     * @param security none (a local test server only), starttls (port 587) or ssl (port 465)
     */
    public SmtpMailSender(String host, int port, String user, String password, String from, String security) {
        this.host = host;
        this.from = from;
        Properties p = new Properties();
        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
        if ("ssl".equals(security)) {
            p.put("mail.smtp.ssl.enable", "true");
            p.put("mail.smtp.ssl.checkserveridentity", "true");
        } else if ("starttls".equals(security)) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
            p.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        boolean login = user != null && !user.isBlank();
        p.put("mail.smtp.auth", String.valueOf(login));
        this.session = login
                ? Session.getInstance(p, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, password);
                    }
                })
                : Session.getInstance(p);
    }

    @Override
    public void send(Mail mail) throws Exception {
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(mail.to(), false));
            message.setSubject(oneLine(mail.subject()), "UTF-8");
            message.setText(mail.body(), "UTF-8");
            message.setHeader("Auto-Submitted", "auto-generated");
            message.saveChanges();
            message.setHeader("Message-ID", "<outbox-" + mail.id() + "@" + domainOf(from) + ">");
            Transport.send(message);
        } catch (SendFailedException e) {
            if (permanent(e)) {
                throw new Rejected(e.getMessage());
            }
            throw e;
        }
    }

    /**
     * The server named the recipient as invalid (a 5xx answer): the address does not exist or the message is refused,
     * and it will not change on retry. A 4xx answer lands in the unsent-but-valid list instead and is retried.
     */
    private static boolean permanent(SendFailedException e) {
        Address[] invalid = e.getInvalidAddresses();
        return e.getCause() instanceof jakarta.mail.internet.AddressException || (invalid != null && invalid.length > 0);
    }

    private static String oneLine(String subject) {
        return subject.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 ? "localhost" : address.substring(at + 1).replaceAll("[^A-Za-z0-9.-]", "");
    }

    @Override
    public String describe() {
        return host;
    }

    @Override
    public String from() {
        return from;
    }
}
