package com.example.demo.config;

import com.example.demo.task.AttachmentProperties;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import jakarta.servlet.MultipartConfigElement;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // Spring Boot adds Module beans to its own ObjectMapper. That one writes dates as ISO strings
    // (an Instant as "...Z") and applies the spring.jackson.* properties.
    @Bean
    public Hibernate6Module hibernate6Module() {
        return new Hibernate6Module();
    }

    // Uploads up to the attachment limit (Spring's default is 1 MB); the request may be a bit larger
    // than the file. Replaces the spring.servlet.multipart.* properties.
    @Bean
    public MultipartConfigElement multipartConfigElement(AttachmentProperties attachments) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(attachments.maxFileSize());
        factory.setMaxRequestSize(maxUploadRequest(attachments));
        return factory.createMultipartConfig();
    }

    // Tomcat reads only 2 MB of a refused request's body and then drops the connection, which cuts
    // off the answer saying why. Read refused uploads to the end, up to a bit above the limit;
    // larger ones nginx refuses itself (client_max_body_size).
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> readRefusedUploads(AttachmentProperties attachments) {
        long swallow = maxUploadRequest(attachments).toBytes() + DataSize.ofMegabytes(1).toBytes();
        int bytes = (int) Math.min(swallow, Integer.MAX_VALUE);
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                protocol.setMaxSwallowSize(bytes);
            }
        });
    }

    private static DataSize maxUploadRequest(AttachmentProperties attachments) {
        return DataSize.ofBytes(attachments.maxFileSize().toBytes() + DataSize.ofMegabytes(1).toBytes());
    }
}
