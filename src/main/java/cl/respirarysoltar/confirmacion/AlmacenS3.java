package cl.respirarysoltar.confirmacion;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Comprobantes en el bucket S3 rys-comprobantes-<cuenta> (cifrado SSE-S3, sin acceso público). */
public class AlmacenS3 implements AlmacenComprobantes {

    private final S3Client s3;
    private final String bucket;

    public AlmacenS3(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public boolean existe(String clave) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(clave).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void guardar(String clave, String html, Map<String, String> metadatos) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(clave)
                        .contentType("text/html; charset=utf-8")
                        .metadata(metadatos)
                        .build(),
                RequestBody.fromString(html, StandardCharsets.UTF_8));
    }
}
