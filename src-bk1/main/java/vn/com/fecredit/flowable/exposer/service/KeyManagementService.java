package vn.com.fecredit.flowable.exposer.service;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class KeyManagementService {
    // In-memory master key (for demo). In real world use KMS.
    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public KeyManagementService() {
        // 256-bit master key
        byte[] mk = new byte[32];
        random.nextBytes(mk);
        this.masterKey = mk;
    }

    public SecretKey generateDataKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        return kg.generateKey();
    }

    // Returns Base64(iv + ciphertext) so it can be stored as CLOB text
    public String encryptWithDataKey(byte[] plaintext, SecretKey dataKey) throws Exception {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, dataKey, spec);
        byte[] cipherText = cipher.doFinal(plaintext);
        byte[] out = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
        return Base64.getEncoder().encodeToString(out);
    }

    // Accepts Base64(iv + ciphertext)
    public byte[] decryptWithDataKey(String cipherWithIvB64, SecretKey dataKey) throws Exception {
        byte[] cipherWithIv = Base64.getDecoder().decode(cipherWithIvB64);
        byte[] iv = new byte[12];
        System.arraycopy(cipherWithIv, 0, iv, 0, iv.length);
        byte[] cipherText = new byte[cipherWithIv.length - iv.length];
        System.arraycopy(cipherWithIv, iv.length, cipherText, 0, cipherText.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, dataKey, new GCMParameterSpec(128, iv));
        return cipher.doFinal(cipherText);
    }

    // Envelope encryption: encrypt dataKey with masterKey
    // Wraps data key with master key and returns Base64(iv + wrappedKey)
    public String wrapDataKey(SecretKey dataKey) throws Exception {
        SecretKey mk = new SecretKeySpec(masterKey, "AES");
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, mk, new GCMParameterSpec(128, iv));
        byte[] wrapped = cipher.doFinal(dataKey.getEncoded());
        byte[] out = new byte[iv.length + wrapped.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(wrapped, 0, out, iv.length, wrapped.length);
        return Base64.getEncoder().encodeToString(out);
    }

    // Accepts Base64(iv + wrappedKey)
    public SecretKey unwrapDataKey(String wrappedB64) throws Exception {
        SecretKey mk = new SecretKeySpec(masterKey, "AES");
        byte[] wrapped = Base64.getDecoder().decode(wrappedB64);
        byte[] iv = new byte[12];
        System.arraycopy(wrapped, 0, iv, 0, iv.length);
        byte[] wrappedCipher = new byte[wrapped.length - iv.length];
        System.arraycopy(wrapped, iv.length, wrappedCipher, 0, wrappedCipher.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, mk, new GCMParameterSpec(128, iv));
        byte[] dataKeyBytes = cipher.doFinal(wrappedCipher);
        return new SecretKeySpec(dataKeyBytes, "AES");
    }
}
