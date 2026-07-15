package com.zlecaf.escrow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * S3 client pointed at the MinIO service. Kept here so that MinIO/S3 types stay
 * confined to this class and the storage adapter.
 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(
            @Value("${escrow.storage.endpoint}") String endpoint,
            @Value("${escrow.storage.access-key}") String accessKey,
            @Value("${escrow.storage.secret-key}") String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // MinIO serves buckets as path segments, not DNS subdomains; the
                // SDK defaults to virtual-host style since 2.18, so opt back out.
                .forcePathStyle(true)
                // MinIO ignores the region but the SDK requires one to sign.
                .region(Region.US_EAST_1)
                .build();
    }
}
