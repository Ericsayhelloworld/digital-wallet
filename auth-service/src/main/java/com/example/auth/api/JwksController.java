package com.example.auth.api;

import com.example.auth.config.RsaKeys;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** AUTH-4: public keys for wallet-service and notification-service. */
@RestController
public class JwksController {

    private final RsaKeys keys;

    public JwksController(RsaKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.publicJwks();
    }
}
