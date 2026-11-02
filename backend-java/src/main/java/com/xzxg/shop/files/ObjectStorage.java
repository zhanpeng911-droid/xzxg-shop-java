package com.xzxg.shop.files;

import com.xzxg.shop.config.ShopProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.springframework.stereotype.Component;

/**
 * MinIO adapter mirroring the Go objectstore package: bucket is created lazily and
 * object keys are preserved verbatim.
 */
@Component
public class ObjectStorage {

    private final MinioClient client;
    private final String bucket;

    public ObjectStorage(ShopProperties properties) {
        ShopProperties.Minio minio = properties.getFiles().getMinio();
        String endpoint = minio.getEndpoint();
        if (endpoint != null && !endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = (minio.isUseSsl() ? "https://" : "http://") + endpoint;
        }
        this.bucket = minio.getBucket();
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }

    public void put(String objectKey, byte[] content, String contentType) throws Exception {
        ensureBucket();
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                .contentType(contentType)
                .build());
    }

    public InputStream get(String objectKey) throws Exception {
        return client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }

    public void ensureBucket() throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
