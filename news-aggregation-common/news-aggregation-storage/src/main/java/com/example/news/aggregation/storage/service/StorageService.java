package com.example.news.aggregation.storage.service;

import java.io.InputStream;

/**
 * 存储服务接口
 */
public interface StorageService {
    /**
     * 从 URL 下载文件并上传到存储服务
     *
     * @param sourceUrl  源文件 URL
     * @param folder     存储文件夹（如来源名称）
     * @param fileName   文件名（不含扩展名）
     * @return 上传后的完整访问 URL
     */
    String uploadFromUrl(String sourceUrl,String folder,String fileName);

    /**
     * 上传文件流
     *
     * @param inputStream 文件输入流
     * @param folder      存储文件夹
     * @param fileName    文件名（含扩展名）
     * @param contentType 文件类型
     * @return 上传后的完整访问 URL
     */
    String upload(InputStream inputStream,String folder,String fileName,String contentType);

    /**
     * 删除文件
     *
     * @param objectPath 文件路径
     * @return 是否删除成功
     */
    boolean delete(String objectPath);

    /**
     * 检查文件是否存在
     *
     * @param objectPath 文件路径
     * @return 是否存在
     */
    boolean exists(String objectPath);

}
