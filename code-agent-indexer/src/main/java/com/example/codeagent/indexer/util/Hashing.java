package com.example.codeagent.indexer.util;

import com.example.codeagent.core.exception.IndexingException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 哈希工具：用于内容指纹与嵌入缓存键。 */
public final class Hashing {

    private Hashing() {
    }

    /** 计算字符串的 sha256 十六进制摘要。 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IndexingException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
