package com.codeoffer.backend.settings;

import com.codeoffer.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** LLM key 静态加密:AES-GCM,主密钥 LLM_MASTER_KEY(Base64 32 字节),缺失启动快速失败。 */
@Component
public class CryptoService {

    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public CryptoService(@Value("${LLM_MASTER_KEY:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException("缺少 LLM_MASTER_KEY:请生成 32 字节 Base64 密钥写入 config/.env");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("LLM_MASTER_KEY 不是合法 Base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("LLM_MASTER_KEY 必须为 32 字节(Base64 编码)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "密钥加密失败");
        }
    }

    public String decrypt(String stored) {
        try {
            if (stored == null || !stored.startsWith(PREFIX)) {
                throw new IllegalArgumentException("bad prefix");
            }
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "密钥解密失败,请检查 LLM_MASTER_KEY 是否变更");
        }
    }

    public static String mask(String plain) {
        if (plain == null || plain.length() <= 8) {
            return "****";
        }
        return plain.substring(0, 3) + "****" + plain.substring(plain.length() - 4);
    }
}