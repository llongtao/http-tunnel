/*
 * htunnel - A simple HTTP tunnel
 * https://github.com/nicolas-dutertry/htunnel
 *
 * Written by Nicolas Dutertry.
 *
 * This file is provided under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.dutertry.htunnel.common.crypto;

import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

public class CryptoUtils {
    private static final String AES_KEY_ALGO = "AES";
    private static final String AES_ENCRYPT_ALGO = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH_BYTE = 16;
    private static final String RSA_CRYPT_ALG = "RSA/ECB/PKCS1Padding";

    public static SecretKey generateAESKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(AES_KEY_ALGO);
        keyGen.init(256);
        return keyGen.generateKey();
    }

    public static String encodeAESKey(SecretKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static SecretKey decodeAESKey(String encodedKey) {
        return new SecretKeySpec(Base64.getDecoder().decode(encodedKey), AES_KEY_ALGO);
    }

    public static byte[] encryptAES(byte[] pText, SecretKey secret) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(AES_ENCRYPT_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, secret);
        byte[] iv = cipher.getIV();
        byte[] encryptedText = cipher.doFinal(pText);

        ByteBuffer bb = ByteBuffer.allocate(iv.length + encryptedText.length);
        bb.put(iv);
        bb.put(encryptedText);
        return bb.array();
    }

    public static byte[] decryptAES(byte[] cText, SecretKey secret) throws GeneralSecurityException {
        ByteBuffer bb = ByteBuffer.wrap(cText);

        byte[] iv = new byte[IV_LENGTH_BYTE];
        bb.get(iv);

        byte[] cipherText = new byte[bb.remaining()];
        bb.get(cipherText);

        Cipher cipher = Cipher.getInstance(AES_ENCRYPT_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
        byte[] plainText = cipher.doFinal(cipherText);
        return plainText;
    }

    public static PrivateKey readRSAPrivateKey(String priKey) throws IOException {
        try {
            PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(priKey));
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            return rsa.generatePrivate(pkcs8EncodedKeySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("priKey解析失败",e);
        }
    }

    public static PublicKey readRSAPublicKey(String pubKey) {
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(pubKey));
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            return rsa.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("pubKey解析失败");
        }
    }

    public static byte[] decryptRSA(byte[] crypted, PublicKey publicKey) throws GeneralSecurityException {
        byte[] decoded = Base64.getDecoder().decode(crypted);
        Cipher cipher = Cipher.getInstance(RSA_CRYPT_ALG);
        cipher.init(Cipher.DECRYPT_MODE, publicKey);
        return cipher.doFinal(decoded);
    }

    public static byte[] encryptRSA(byte[] decrypted, PrivateKey privateKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(RSA_CRYPT_ALG);
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        byte[] crypted = cipher.doFinal(decrypted);
        return Base64.getEncoder().encode(crypted);
    }
}
