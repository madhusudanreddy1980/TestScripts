package com.batchauto.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.batchauto.errors.BatchAutoException.StorageException;
import com.batchauto.errors.BatchAutoException.TransientStorageException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Local filesystem adapter for development and the simulated batch processor. Copies to a
 * "*.part" file and renames atomically, so a watcher never sees a half-written file.
 */
public final class LocalFilesystemAdapter implements InboundStorageAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;

    public LocalFilesystemAdapter(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException("cannot create storage root " + root, e);
        }
    }

    @Override
    public String provider() { return "local"; }

    public Path root() { return root; }

    Path resolve(String destination) {
        Path p = root.resolve(destination).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new StorageException("destination escapes storage root: " + destination);
        }
        return p;
    }

    private Path metaPath(Path p) {
        return p.getParent().resolve(".meta").resolve(p.getFileName() + ".json");
    }

    @Override
    public UploadResult uploadFile(Path file, String destination, Map<String, String> metadata) {
        Path target = resolve(destination);
        if (Files.exists(target)) {
            throw new StorageException("refusing to overwrite existing inbound file " + destination);
        }
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            String sha = StorageSupport.sha256(tmp);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sha256", sha);
            if (metadata != null) {
                meta.putAll(metadata);
            }
            Path mp = metaPath(target);
            Files.createDirectories(mp.getParent());
            Files.writeString(mp, JSON.writeValueAsString(meta));
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return new UploadResult(destination, target.toUri().toString(), Files.size(target), sha, System.currentTimeMillis());
        } catch (IOException e) {
            throw new TransientStorageException(e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyFile(String destination, String expectedSha256) {
        Path p = resolve(destination);
        return Files.isRegularFile(p) && (expectedSha256 == null || StorageSupport.sha256(p).equals(expectedSha256));
    }

    @Override
    public Map<String, Object> getFileMetadata(String destination) {
        Path p = resolve(destination);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("size", Files.size(p));
            meta.put("modified", Files.getLastModifiedTime(p).toString());
            Path mp = metaPath(p);
            if (Files.isRegularFile(mp)) {
                meta.putAll(JSON.readValue(Files.readString(mp), new TypeReference<Map<String, Object>>() { }));
            }
            return meta;
        } catch (IOException e) {
            throw new StorageException("cannot read metadata for " + destination, e);
        }
    }

    @Override
    public boolean deleteTestFile(String destination) {
        Path p = resolve(destination);
        try {
            boolean existed = Files.deleteIfExists(p);
            Files.deleteIfExists(metaPath(p));
            return existed;
        } catch (IOException e) {
            throw new StorageException("cannot delete " + destination, e);
        }
    }

    @Override
    public String describe(String destination) { return resolve(destination).toString(); }
}
