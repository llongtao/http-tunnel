package com.dutertry.htunnel.common.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoUtils {
    private static final String KEY_ALGO = "AES";
    private static final String ENCRYPT_ALGO = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH_BYTE = 16;
    
    public static SecretKey generateKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(KEY_ALGO);
        keyGen.init(256);
        return keyGen.generateKey();
    }
    
    public static String encodeKey(SecretKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
    
    public static SecretKey decodeKey(String encodedKey) {
        return new SecretKeySpec(Base64.getDecoder().decode(encodedKey), KEY_ALGO);
    }
    
    public static byte[] encrypt(byte[] pText, SecretKey secret) throws GeneralSecurityException  {
        Cipher cipher = Cipher.getInstance(ENCRYPT_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] iv = cipher.getIV();
        byte[] encryptedText = cipher.doFinal(pText);
        
        ByteBuffer bb = ByteBuffer.allocate(iv.length + encryptedText.length);
        bb.put(iv);
        bb.put(encryptedText);
        return bb.array();
    }
    
    public static byte[] decrypt(byte[] cText, SecretKey secret) throws GeneralSecurityException {
        ByteBuffer bb = ByteBuffer.wrap(cText);

        byte[] iv = new byte[IV_LENGTH_BYTE];
        bb.get(iv);

        byte[] cipherText = new byte[bb.remaining()];
        bb.get(cipherText);
        
        Cipher cipher = Cipher.getInstance(ENCRYPT_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
        byte[] plainText = cipher.doFinal(cipherText);
        return plainText;
    }
}
