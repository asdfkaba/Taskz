package de.morfic.taskz;

import de.morfic.taskz.config.RsaKeyProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

// Throwaway key pair used by the security config under test.
@TestConfiguration
public class TestRsaKeys {

    @Bean
    @Primary
    RsaKeyProperties rsaKeyProperties() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new RsaKeyProperties((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
    }
}
