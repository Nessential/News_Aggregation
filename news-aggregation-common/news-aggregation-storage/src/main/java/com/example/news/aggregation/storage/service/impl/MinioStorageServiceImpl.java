package com.example.news.aggregation.storage.service.impl;

import com.example.news.aggregation.storage.config.StorageProperties;
import com.example.news.aggregation.storage.exception.StorageErrorCode;
import com.example.news.aggregation.storage.exception.StorageException;
import com.example.news.aggregation.storage.service.StorageService;
import io.minio.*;
import io.minio.errors.*;
import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Random;


@Service
@ConditionalOnProperty(prefix = "storage.minio", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class MinioStorageServiceImpl implements StorageService {


    private final StorageProperties properties;
    private MinioClient minioClient;
    private static final Random RANDOM = new Random();


    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    @PostConstruct //    Bean 创建完成、依赖注入完成后自动执行一次。
    public void init(){
        // 打印配置信息用于排查问题
        log.info("MinIO 配置加载:");
        log.info("  endpoint: {}", properties.getEndpoint());
        log.info("  accessKey: {}", properties.getAccessKey());
        log.info("  bucket: {}", properties.getBucket());
        log.info("  connectTimeout: {}", properties.getConnectTimeout());
        log.info("  readTimeout: {}", properties.getReadTimeout());
        
        this.minioClient = MinioClient.builder().endpoint(properties.getEndpoint()).credentials(properties.getAccessKey(),properties.getSecretKey()).build();
        minioClient.setTimeout(properties.getConnectTimeout(), properties.getReadTimeout(), properties.getReadTimeout());
        ensureBucketExist(minioClient, properties.getBucket());
    }



    /**
     * 确保存储桶存在
     */
    private void ensureBucketExist(MinioClient minioClient,String bucket){

        try{
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build()
                );
                log.info("创建存储桶: {}", bucket);
            }
        }
        catch (Exception e){
            log.error("检查/创建存储桶失败: {}", bucket, e);
            throw new StorageException("存储桶初始化失败", StorageErrorCode.CONNECTION_FAILED);
        }

    }

    /**
     * 通过图片url 获取图片文件并保存
     * @param sourceUrl  源文件 URL
     * @param folder     存储文件夹（如来源名称）
     * @param fileName   文件名（不含扩展名）
     * @return
     */
    @Override
    public String uploadFromUrl(String sourceUrl, String folder, String fileName) {
        if(sourceUrl ==null || sourceUrl.isEmpty()){
            return null;
        }

        try{
            //        设置代理请求头
            URL url = new URL(sourceUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent",USER_AGENT);
            connection.setConnectTimeout((int) properties.getConnectTimeout());
            connection.setReadTimeout((int) properties.getReadTimeout());

//            获取文件拓展名
            String contentType =  connection.getContentType();
            if(contentType ==null){
                contentType = "image/jpeg";
            }
            String extension = getExtensionFromContentType(contentType);
            if (extension == null) {
                extension = getExtensionFromUrl(sourceUrl);
            }

            String sanitizedFileName  =  sanitizeFileName(fileName);

            String finalFileName = new StringBuilder()
                .append(sanitizedFileName)
                .append("_")
                .append(RANDOM.nextInt(100000))
                .append(extension).toString();

            try (InputStream inputStream = connection.getInputStream()){
                return upload(inputStream,folder,finalFileName,contentType);
            }
        } catch (IOException e) {
            log.error("从 URL 下载文件失败: {}", sourceUrl, e);
            throw new StorageException("文件下载失败, url=" + sourceUrl, e, StorageErrorCode.DOWNLOAD_FAILED);
        }


    }


    /**
     * 生成文件名并将文件流上传到minio
     * @param inputStream 文件输入流
     * @param folder      存储文件夹
     * @param fileName    文件名（含扩展名）
     * @param contentType 文件类型
     * @return 相对路径，如 bucket/folder/filename.jpg
     */
    @Override
    public String upload(InputStream inputStream, String folder, String fileName, String contentType) {
        String objectPath = new StringBuilder(sanitizeFileName(folder)).append("/").append(fileName).toString();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectPath)
                    .stream(inputStream, -1, 10485760).contentType(contentType).build());

            // 返回相对路径（包含bucket）
            String relativePath = properties.getBucket() + "/" + objectPath;
            log.info("文件上传成功: {}", relativePath);
            return relativePath;
        } catch (Exception e) {
            log.error("文件上传失败: {}/{}", folder, fileName, e);
            throw new StorageException("文件上传失败", e, StorageErrorCode.UPLOAD_FAILED);
        }

    }

    @Override
    public boolean delete(String objectPath) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(properties.getBucket()).object(objectPath).build());
            log.info("文件删除成功");
            return true;
        } catch (Exception e) {
            log.error("文件删除失败，{}",objectPath,e);
            return false;
        }

    }

    @Override
    public boolean exists(String objectPath) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectPath)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if (e.errorResponse().code().equals("NoSuchKey")) {
                return false;
            }
            throw new StorageException("检查文件存在失败", e, StorageErrorCode.CONNECTION_FAILED);
        } catch (Exception e) {
            throw new StorageException("检查文件存在失败", e, StorageErrorCode.CONNECTION_FAILED);
        }
    }


    /**
     * 从 Content-Type 获取文件扩展名
     */
    private String getExtensionFromContentType(String contentType) {
        if (contentType == null) return null;
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> null;
        };
    }

    /**
     * 从 URL 获取文件扩展名
     */
    private String getExtensionFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            int lastDot = path.lastIndexOf('.');
            if (lastDot > 0 && lastDot < path.length() - 1) {
                String ext = path.substring(lastDot).toLowerCase();
                if (ext.matches("\\.(jpg|jpeg|png|gif|webp|svg)")) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
        }
        return ".jpg"; // 默认扩展名
    }

    private String sanitizeFileName(String fileName) {
        if(fileName ==null || fileName.isEmpty()){
            return "unnamed";
        }
        // 移除非法字符，保留中文、字母、数字、下划线、连字符
        String sanitized = fileName.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9_-]", "_")
                .replaceAll("_+", "_");
        // 限制长度（使用处理后的字符串长度）
        return sanitized.substring(0, Math.min(sanitized.length(), 50));
    }

    @Override
    public String getAccessUrl(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        // 如果已经是完整URL，直接返回
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        // 拼接完整URL: endpoint + path
        return properties.getEndpoint() + "/" + path;
    }
}
