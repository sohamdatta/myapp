package com.example.loginapp.core;

/** Sends one email. The outbox decides when; this only talks to a mail server (or, in tests, records the message). */
public interface MailSender {

    /** One message to send. {@code id} is the outbox row, used as the Message-ID so a retry is recognisable. */
    record Mail(long id, String to, String subject, String body) {}

    /** The mail server refused the message for good (a 5xx answer): retrying will not help. */
    final class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    /**
     * @throws Rejected when the server says no for good
     * @throws Exception any other failure (cannot connect, timeout, 4xx): worth trying again later
     */
    void send(Mail mail) throws Exception;

    /** Where the mail goes, for the Email page: a host name, or null when none is set up. */
    String describe();

    /** The address mail is sent from. */
    String from();
}
