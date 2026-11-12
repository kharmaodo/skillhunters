package com.skillhunters.communication;

import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpContactTransport implements ContactTransport {
    private final JavaMailSenderImpl mail=new JavaMailSenderImpl();private final boolean enabled;private final String sender;
    public SmtpContactTransport(@Value("${app.smtp.enabled:false}") boolean enabled,@Value("${app.smtp.host:localhost}") String host,
            @Value("${app.smtp.port:1025}") int port,@Value("${app.smtp.username:}") String username,@Value("${app.smtp.password:}") String password,
            @Value("${app.smtp.starttls:true}") boolean tls,@Value("${app.smtp.sender:no-reply@example.com}") String sender) {
        this.enabled=enabled;this.sender=sender;mail.setHost(host);mail.setPort(port);mail.setUsername(username);mail.setPassword(password);
        var p=mail.getJavaMailProperties();p.setProperty("mail.smtp.auth",Boolean.toString(!username.isBlank()));
        p.setProperty("mail.smtp.starttls.enable",Boolean.toString(tls));p.setProperty("mail.smtp.starttls.required",Boolean.toString(tls));
        p.setProperty("mail.smtp.connectiontimeout","5000");p.setProperty("mail.smtp.timeout","5000");p.setProperty("mail.smtp.writetimeout","5000");
        p.setProperty("mail.smtp.sendpartial","false");
    }
    public String sender(){return sender;}
    public boolean enabled(){return enabled;}
    public Result send(Envelope e){
        if(!enabled)return new Result("FAILED","SMTP_DISABLED");
        jakarta.mail.internet.MimeMessage message;
        try {
            message=mail.createMimeMessage();var helper=new MimeMessageHelper(message,false,"UTF-8");
            helper.setFrom(address(e.sender()));helper.setTo(address(e.recipient()));helper.setReplyTo(address(e.replyTo()));
            helper.setSubject(e.subject());helper.setText(e.body(),false); // Plain text: no remote pixels, HTML or attachments.
            message.saveChanges();message.setHeader("Message-ID",e.messageId());
        }catch(Exception invalid){return new Result("FAILED","SMTP_PREPARATION_FAILED");}
        try {mail.send(message);return new Result("ACCEPTED","SMTP_ACCEPTED");}
        catch(Exception ambiguous){return new Result("UNCERTAIN","SMTP_RESULT_UNCERTAIN");} // Never retry automatically, even after a connection exception.
    }
    static InternetAddress address(String value) throws jakarta.mail.internet.AddressException {
        if(value==null||value.length()>254||value.codePoints().anyMatch(Character::isISOControl)||!value.matches("[^\\s<>@,;]+@[^\\s<>@,;]+\\.[^\\s<>@,;]+"))throw new jakarta.mail.internet.AddressException("INVALID_ADDRESS");
        var address=new InternetAddress(value,true);address.validate();return address;
    }
}
