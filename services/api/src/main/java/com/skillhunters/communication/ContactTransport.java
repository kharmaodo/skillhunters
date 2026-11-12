package com.skillhunters.communication;

public interface ContactTransport {
    record Envelope(String sender,String recipient,String replyTo,String subject,String body,String messageId) {}
    record Result(String status,String code) {}
    String sender();
    boolean enabled();
    Result send(Envelope message);
}
