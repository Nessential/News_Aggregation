package com.example.news.aggregation.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据库连接测试类
 * 用于验证数据库配置是否正确，连接是否可用
 *
 * @author Hollis
 */
@SpringBootTest
public class DatabaseConnectionTest {

    @Autowired(required = false)
    private DataSource dataSource;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    /**
     * 测试数据源是否成功注入
     */
    @Test
    public void testDataSourceNotNull() {
        assertNotNull(dataSource, "数据源未成功注入，请检查配置");
        System.out.println("✅ 数据源注入成功");
    }

    /**
     * 测试数据源类型是否为 Druid
     */
    @Test
    public void testDataSourceType() {
        assertNotNull(dataSource, "数据源不能为空");
        assertTrue(dataSource instanceof DruidDataSource, 
                "数据源类型应该是 DruidDataSource，实际类型：" + dataSource.getClass().getName());
        System.out.println("✅ 数据源类型正确：DruidDataSource");
    }

    /**
     * 测试数据库连接是否可用
     */
    @Test
    public void testDatabaseConnection() throws SQLException {
        assertNotNull(dataSource, "数据源不能为空");
        
        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "获取数据库连接失败");
            assertFalse(connection.isClosed(), "数据库连接已关闭");
            
            System.out.println("✅ 数据库连接成功");
            
            // 获取数据库元数据
            DatabaseMetaData metaData = connection.getMetaData();
            System.out.println("📊 数据库信息：");
            System.out.println("   - 数据库产品名称: " + metaData.getDatabaseProductName());
            System.out.println("   - 数据库版本: " + metaData.getDatabaseProductVersion());
            System.out.println("   - 驱动名称: " + metaData.getDriverName());
            System.out.println("   - 驱动版本: " + metaData.getDriverVersion());
            System.out.println("   - 连接URL: " + metaData.getURL());
            System.out.println("   - 用户名: " + metaData.getUserName());
        }
    }

    /**
     * 测试 Druid 连接池配置
     */
    @Test
    public void testDruidPoolConfiguration() {
        assertNotNull(dataSource, "数据源不能为空");
        assertTrue(dataSource instanceof DruidDataSource, "数据源应该是 DruidDataSource");
        
        DruidDataSource druidDataSource = (DruidDataSource) dataSource;
        
        System.out.println("📊 Druid 连接池配置：");
        System.out.println("   - 初始连接数: " + druidDataSource.getInitialSize());
        System.out.println("   - 最小空闲连接数: " + druidDataSource.getMinIdle());
        System.out.println("   - 最大活动连接数: " + druidDataSource.getMaxActive());
        System.out.println("   - 最大等待时间: " + druidDataSource.getMaxWait() + "ms");
        System.out.println("   - 验证查询SQL: " + druidDataSource.getValidationQuery());
        
        // 验证关键配置
        assertTrue(druidDataSource.getMaxActive() > 0, "最大连接数应该大于0");
        assertTrue(druidDataSource.getInitialSize() <= druidDataSource.getMaxActive(), 
                "初始连接数不能大于最大连接数");
        
        System.out.println("✅ Druid 连接池配置验证通过");
    }

    /**
     * 测试执行简单的 SQL 查询
     */
    @Test
    public void testSimpleQuery() {
        assertNotNull(jdbcTemplate, "JdbcTemplate 未成功注入");
        
        // 执行简单查询
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertEquals(1, result, "查询结果应该为1");
        
        System.out.println("✅ 简单查询执行成功");
    }

    /**
     * 测试查询当前数据库
     */
    @Test
    public void testCurrentDatabase() {
        assertNotNull(jdbcTemplate, "JdbcTemplate 未成功注入");
        
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(database, "当前数据库名称不能为空");
        
        System.out.println("✅ 当前使用的数据库: " + database);
    }

    /**
     * 测试查询数据库版本
     */
    @Test
    public void testDatabaseVersion() {
        assertNotNull(jdbcTemplate, "JdbcTemplate 未成功注入");
        
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertNotNull(version, "数据库版本信息不能为空");
        
        System.out.println("✅ MySQL 版本: " + version);
    }

    /**
     * 测试连接池的获取和释放
     */
    @Test
    public void testConnectionPoolGetAndRelease() throws SQLException {
        assertNotNull(dataSource, "数据源不能为空");
        assertTrue(dataSource instanceof DruidDataSource, "数据源应该是 DruidDataSource");
        
        DruidDataSource druidDataSource = (DruidDataSource) dataSource;
        
        int initialActiveCount = druidDataSource.getActiveCount();
        System.out.println("初始活动连接数: " + initialActiveCount);
        
        // 获取连接
        Connection connection = dataSource.getConnection();
        assertNotNull(connection, "连接不能为空");
        
        int afterGetActiveCount = druidDataSource.getActiveCount();
        System.out.println("获取连接后活动连接数: " + afterGetActiveCount);
        
        // 释放连接
        connection.close();
        
        int afterCloseActiveCount = druidDataSource.getActiveCount();
        System.out.println("释放连接后活动连接数: " + afterCloseActiveCount);
        
        System.out.println("✅ 连接池获取和释放测试通过");
    }
}

