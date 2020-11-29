package com.dutertry.htunnel.common.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;

import org.junit.Assert;
import org.junit.Test;


public class CryptoUtilsTest {
    @Test
    public void testEncryption() throws GeneralSecurityException {
        SecretKey key = CryptoUtils.generateKey();
        
        String s = "Hello World";
        byte[] encrypted = CryptoUtils.encrypt(s.getBytes(), key);
        
        String encodedKey = CryptoUtils.encodeKey(key);
        key = CryptoUtils.decodeKey(encodedKey);
        byte[] decrypted = CryptoUtils.decrypt(encrypted, key);
        
        Assert.assertEquals(s, new String(decrypted));  
    }
}
