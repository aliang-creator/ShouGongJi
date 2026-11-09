package com.sg.shougongji.repository;

import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public final class WorkLogDatabase {
    private static final String SCHEMA = "com/sg/shougongji/repository/schema.sql";

    private final Path databaseFile;
    private final SqlSessionFactory sessions;

    public WorkLogDatabase(Path databaseFile) throws SQLException {
        this.databaseFile = databaseFile.toAbsolutePath();
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(3000);
        // 写事务在首次读写前获得写锁，避免两个操作同时根据旧状态更新。
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource dataSource = new SQLiteDataSource(sqlite);
        dataSource.setUrl("jdbc:sqlite:" + this.databaseFile);
        sessions = createSessionFactory(dataSource);
        initializeSchema(dataSource);
    }

    private static SqlSessionFactory createSessionFactory(SQLiteDataSource dataSource) throws SQLException {
        JdbcTransactionFactory transactions = new JdbcTransactionFactory();
        Properties transactionOptions = new Properties();
        transactionOptions.setProperty("skipSetAutoCommitOnClose", "true");
        transactions.setProperties(transactionOptions);

        Configuration configuration = new Configuration(new Environment("local", transactions, dataSource));
        // 云效令牌是 SQL 参数，关闭 MyBatis 的参数日志。
        configuration.setLogImpl(NoLoggingImpl.class);
        try {
            configuration.addMapper(TimerMapper.class);
            configuration.addMapper(YunxiaoMapper.class);
            return new SqlSessionFactoryBuilder().build(configuration);
        } catch (PersistenceException failure) {
            throw new SQLException("无法加载本地数据库映射。", failure);
        }
    }

    private static void initializeSchema(SQLiteDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Reader schema = Resources.getResourceAsReader(SCHEMA)) {
            ScriptRunner runner = new ScriptRunner(connection);
            runner.setAutoCommit(false);
            runner.setStopOnError(true);
            runner.setLogWriter(null);
            runner.setErrorLogWriter(null);
            runner.runScript(schema);
        } catch (IOException | PersistenceException failure) {
            throw new SQLException("无法初始化本地数据库。", failure);
        }
    }

    void restrictFileAccess() throws SQLException {
        try {
            if (Files.getFileAttributeView(databaseFile, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(databaseFile, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | SecurityException failure) {
            throw new SQLException("无法将本地数据库限制为当前用户访问。", failure);
        }
    }

    <M, R> R read(Class<M> mapperType, MapperQuery<M, R> query) throws SQLException {
        try (SqlSession session = sessions.openSession(true)) {
            return query.run(session.getMapper(mapperType));
        } catch (PersistenceException failure) {
            throw new SQLException("无法读取本地记录库。", failure);
        }
    }

    <M> void write(Class<M> mapperType, MapperWrite<M> write) throws SQLException {
        writeReturning(mapperType, mapper -> {
            write.run(mapper);
            return null;
        });
    }

    <M, R> R writeReturning(Class<M> mapperType, MapperQuery<M, R> write) throws SQLException {
        try (SqlSession session = sessions.openSession(false)) {
            try {
                R result = write.run(session.getMapper(mapperType));
                session.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    session.rollback();
                } catch (PersistenceException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } catch (PersistenceException failure) {
            throw new SQLException("无法写入本地记录库。", failure);
        }
    }

    @FunctionalInterface
    interface MapperQuery<M, R> {
        R run(M mapper) throws SQLException;
    }

    @FunctionalInterface
    interface MapperWrite<M> {
        void run(M mapper) throws SQLException;
    }
}
