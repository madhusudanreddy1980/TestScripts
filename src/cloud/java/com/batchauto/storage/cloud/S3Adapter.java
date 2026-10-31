package com.batchauto.storage.cloud;

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

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Amazon S3. Credentials: default provider chain (IAM role / IRSA / SSO).
 * Uses S3 conditional writes (If-None-Match: *) so a retry can never overwrite.
 * STATUS: Phase 6 — not yet exercised against a real bucket.
 */
public final class S3Adapter implements InboundStorageAdapter {
    private final String bucket;
    private final S3Client s3;

    public S3Adapter(CloudProfile p) {
        this.bucket = p.bucket();
        S3ClientBuilder b = S3Client.builder();
        if (p.region() != null) {
            b.region(Region.of(p.region()));
        }
        this.s3 = b.build();
    }

    @Override
    public String provider() { return "s3"; }

    @Override
    public UploadResult uploadFile(Path file, String destination, Map<String, String> metadata) {
        String sha = StorageSupport.sha256(file);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sha256", sha);
        if (metadata != null) {
            meta.putAll(metadata);
        }
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(destination).metadata(meta).ifNoneMatch("*").build(),
                    RequestBody.fromFile(file));
            return new UploadResult(destination, "s3://" + bucket + "/" + destination, Files.size(file), sha,
                    System.currentTimeMillis());
        } catch (S3Exception e) {
            int code = e.statusCode();
            if (code == 412 || code == 409) {
                throw new StorageException("object already exists: s3://" + bucket + "/" + destination, e);
            }
            if (code == 429 || code >= 500) {
                throw new TransientStorageException(e.getMessage(), e);
            }
            throw new StorageException("S3 upload failed: " + e.getMessage(), e);
        } catch (SdkClientException e) {
            throw new TransientStorageException(e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new TransientStorageException(e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> getFileMetadata(String destination) {
        try {
            HeadObjectResponse h = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(destination).build());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", h.contentLength());
            m.put("updated", String.valueOf(h.lastModified()));
            m.putAll(h.metadata());
            return m;
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public boolean verifyFile(String destination, String expectedSha256) {
        Map<String, Object> m = getFileMetadata(destination);
        return m != null && (expectedSha256 == null || expectedSha256.equals(m.get("sha256")));
    }

    @Override
    public boolean deleteTestFile(String destination) {
        if (getFileMetadata(destination) == null) {
            return false;
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(destination).build());
        return true;
    }
}
