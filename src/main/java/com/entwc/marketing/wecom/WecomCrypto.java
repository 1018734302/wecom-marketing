package com.entwc.marketing.wecom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 企微回调的签名校验与报文解密。
 * 算法遵循企微官方加解密方案：SHA1 校验 + AES-256-CBC（PKCS7，块长 32）。
 */
public final class WecomCrypto {

    private WecomCrypto() {
    }

    /** 计算 SHA1 签名：token、timestamp、nonce、encrypt 字典序拼接后取 SHA1 十六进制 */
    public static String signature(String token, String timestamp, String nonce, String encrypt) {
        String[] arr = {token, timestamp, nonce, encrypt};
        Arrays.sort(arr);
        String joined = String.join("", arr);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 计算失败", e);
        }
    }

    /** 校验企微推送的签名是否合法 */
    public static boolean verify(String token, String timestamp, String nonce, String encrypt, String msgSignature) {
        if (token == null || encrypt == null || msgSignature == null) {
            return false;
        }
        return signature(token, timestamp, nonce, encrypt).equals(msgSignature);
    }

    /**
     * 解密企微报文，返回明文字段（随机串与 corpId 已被剥离）。
     */
    public static String decrypt(String encodingAesKey, String encrypted) {
        try {
            byte[] key = Base64.getDecoder().decode(encodingAesKey + "=");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec iv = new IvParameterSpec(Arrays.copyOfRange(key, 0, 16));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv);

            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            int pad = plain[plain.length - 1] & 0xff;
            if (pad < 1 || pad > 32) {
                throw new IllegalArgumentException("非法的填充长度");
            }
            int contentLength = plain.length - pad;

            int xmlLength = ((plain[16] & 0xff) << 24)
                    | ((plain[17] & 0xff) << 16)
                    | ((plain[18] & 0xff) << 8)
                    | (plain[19] & 0xff);
            if (xmlLength < 0 || 20 + xmlLength > contentLength) {
                throw new IllegalArgumentException("非法的消息长度");
            }
            return new String(plain, 20, xmlLength, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("企微消息解密失败", e);
        }
    }
}
