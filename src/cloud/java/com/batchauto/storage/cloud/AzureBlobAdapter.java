package com.batchauto.storage.cloud;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.util.Context;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.batchauto.config.CloudProfile;
import com.batchauto.errors.BatchAutoException.StorageException;
import com.batchauto.errors.BatchAutoException.TransientStorageException;
import com.batchauto.storage.InboundStorageAdapter;
import com.batchauto.storage.StorageSupport;
import com.batchauto.storage.UploadResult;

/**
 * Azure Blob Storage. Credentials: DefaultAzureCredential (managed identity, workload identity, CLI).
 * Uploads with If-None-Match: * so an existing blob is never overwritten.
 * STATUS: Phase 6 — not yet exercised against a real storage account.
 */
public final class AzureBlobAdapter implements InboundStorageAdapter {
    private final String accountUrl;
    private final String containerName;
    private final BlobContainerClient container;

    public AzureBlobAdapter(CloudProfile p) {
        this.accountUrl = p.accountUrl();
        this.containerName = p.container();
        this.container = new BlobServiceClientBuilder()
                .endpoint(p.accountUrl())
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient()
                .getBlobContainerClient(p.container());
    }

    @Override
    public String provider() { return "azure_blob"; }

    @Override
    public UploadResult uploadFile(Path file, String destination, Map<String, String> metadata) {
        String sha = StorageSupport.sha256(file);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sha256", sha);
        if (metadata != null) {
            meta.putAll(metadata);
        }
        BlobClient blob = container.getBlobClient(destination);
        try {
            blob.uploadFromFileWithResponse(new BlobUploadFromFileOptions(file.toString())
                    .setMetadata(meta)
                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")), null, Context.NONE);
            return new UploadResult(destination, accountUrl + "/" + containerName + "/" + destination,
                    Files.size(file), sha, System.currentTimeMillis());
        } catch (BlobStorageException e) {
            int code = e.getStatusCode();
            if (code == 409 || code == 412) {
                throw new StorageException("blob already exists: " + containerName + "/" + destination, e);
            }
            if (code == 429 || code >= 500) {
                throw new TransientStorageException(e.getMessage(), e);
            }
            throw new StorageException("Azure upload failed: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new TransientStorageException(e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> getFileMetadata(String destination) {
        BlobClient blob = container.getBlobClient(destination);
        try {
            BlobProperties props = blob.getProperties();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", props.getBlobSize());
            m.put("updated", String.valueOf(props.getLastModified()));
            if (props.getMetadata() != null) {
                m.putAll(props.getMetadata());
            }
            return m;
        } catch (HttpResponseException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) {
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
        return container.getBlobClient(destination).deleteIfExists();
    }
}
