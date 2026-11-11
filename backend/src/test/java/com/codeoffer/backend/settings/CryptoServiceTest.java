package com.codeoffer.backend.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceTest {

    // 32 字节测试密钥(Base64)
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private final CryptoService crypto = new CryptoService(KEY);

    @Test
    void roundTrip() {
        String enc = crypto.encrypt("sk-secret-123456");
        assertTrue(enc.startsWith("v1:"));
        assertNotEquals("sk-secret-123456", enc);
        assertEquals("sk-secret-123456", crypto.decrypt(enc));
    }

    @Test
    void encrypt_isRandomized() {
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"));
    }

    @Test
    void mask_rules() {
        assertEquals("sk-****3456", CryptoService.mask("sk-secret-123456"));
        assertEquals("****", CryptoService.mask("short"));
        assertEquals("****", CryptoService.mask(null));
    }

    @Test
    void missingKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> new CryptoService(""));
    }

    @Test
    void wrongLengthKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> new CryptoService("AAAA"));
    }
}