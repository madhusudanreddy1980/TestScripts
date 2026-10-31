package com.batchauto.storage;

public record UploadResult(String destination, String uri, long size, String sha256, long uploadedAtEpochMs) { }
