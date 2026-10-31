package com.batchauto.storage.cloud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.batchauto.config.CloudProfile;
import com.batchauto.errors.BatchAutoException.StorageException;
import com.batchauto.errors.BatchAutoException.TransientStorageException;
import com.batchauto.storage.InboundStorageAdapter;
import com.batchauto.storage.StorageSupport;
import com.batchauto.storage.UploadResult;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * Google Cloud Storage. Credentials: Application Default Credentials / workload identity.
 * STATUS: Phase 6 — not yet exercised against a real bucket.
 */
public final class GcsAdapter implements InboundStorageAdapter {
    private final String bucket;
    private final Storage storage;

    public GcsAdapter(CloudProfile p) {
        this.bucket = p.bucket();
        StorageOptions.Builder b = StorageOptions.newBuilder();
        if (p.project() != null) {
            b.setProjectId(p.project());
        }
        this.storage = b.build().getService();
    }

    @Override
    public String provider() { return "gcs"; }

    @Override
    public UploadResult uploadFile(Path file, String destination, Map<String, String> metadata) {
        String sha = StorageSupport.sha256(file);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sha256", sha);
        if (metadata != null) {
            meta.putAll(metadata);
        }
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, destination)).setMetadata(meta).build();
        try {
            storage.createFrom(info, file, Storage.BlobWriteOption.doesNotExist()); // create-only
            return new UploadResult(destination, "gs://" + bucket + "/" + destination, Files.size(file), sha,
                    System.currentTimeMillis());
        } catch (com.google.cloud.storage.StorageException e) {
            if (e.getCode() == 412) {
                throw new StorageException("object already exists: gs://" + bucket + "/" + destination, e);
            }
            if (e.isRetryable() || e.getCode() == 429 || e.getCode() >= 500) {
                throw new TransientStorageException(e.getMessage(), e);
            }
            throw new StorageException("GCS upload failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new TransientStorageException(e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> getFileMetadata(String destination) {
        Blob blob = storage.get(BlobId.of(bucket, destination));
        if (blob == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", blob.getSize());
        m.put("updated", String.valueOf(blob.getUpdateTimeOffsetDateTime()));
        if (blob.getMetadata() != null) {
            m.putAll(blob.getMetadata());
        }
        return m;
    }

    @Override
    public boolean verifyFile(String destination, String expectedSha256) {
        Map<String, Object> m = getFileMetadata(destination);
        return m != null && (expectedSha256 == null || expectedSha256.equals(m.get("sha256")));
    }

    @Override
    public boolean deleteTestFile(String destination) {
        return storage.delete(BlobId.of(bucket, destination));
    }
}
