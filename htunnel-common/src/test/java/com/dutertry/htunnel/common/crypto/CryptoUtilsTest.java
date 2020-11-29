package com.dutertry.htunnel.common.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;

import org.junit.Assert;
import org.junit.Test;


public class CryptoUtilsTest {
    @Test
    public void testEncryption() throws GeneralSecurityException {
        SecretKey key = CryptoUtils.generateAESKey();
        
        String s = "Hello World";
        byte[] encrypted = CryptoUtils.encryptAES(s.getBytes(), key);
        
        String encodedKey = CryptoUtils.encodeAESKey(key);
        key = CryptoUtils.decodeAESKey(encodedKey);
        byte[] decrypted = CryptoUtils.decryptAES(encrypted, key);
        
        Assert.assertEquals(s, new String(decrypted));  
    }
}
