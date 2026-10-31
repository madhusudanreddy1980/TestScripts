package com.batchauto.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.errors.BatchAutoException.StorageException;
import com.batchauto.errors.BatchAutoException.TransientStorageException;

public final class StorageSupport {
    private static final Logger LOG = LoggerFactory.getLogger(StorageSupport.class);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(double seconds);
    }

    public static final Sleeper REAL_SLEEP = s -> {
        try {
            Thread.sleep((long) (s * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private StorageSupport() { }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new StorageException("cannot hash " + file, e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Joins an inbound prefix and a file name, refusing traversal and absolute paths. */
    public static String safeDestination(String prefix, String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.equals(".") || fileName.equals("..")) {
            throw new ConfigException("invalid file name '" + fileName + "'");
        }
        String p = prefix.replaceAll("^/+|/+$", "");
        for (String part : p.split("/")) {
            if (part.equals("..")) {
                throw new ConfigException("unsafe inbound prefix " + prefix);
            }
        }
        return p.isEmpty() ? fileName : p + "/" + fileName;
    }

    /** Bounded retries for transient failures only; anything else surfaces immediately. */
    public static <T> T withRetries(Supplier<T> action, int attempts, double backoffSeconds, String what, Sleeper sleeper) {
        TransientStorageException last = null;
        int max = Math.max(1, attempts);
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                return action.get();
            } catch (TransientStorageException e) {
                last = e;
                LOG.warn("{} failed (attempt {}/{}): {}", what, attempt, max, e.getMessage());
                if (attempt < max) {
                    sleeper.sleep(backoffSeconds * Math.pow(2, attempt - 1));
                }
            }
        }
        throw new StorageException(what + " failed after " + max + " attempts: " + last.getMessage(), last);
    }
}
