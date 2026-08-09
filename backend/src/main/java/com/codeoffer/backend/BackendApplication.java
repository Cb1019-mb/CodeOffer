package com.codeoffer.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		ensureDataDir();
		SpringApplication.run(BackendApplication.class, args);
	}

	/**
	 * SQLite 不会自动创建父目录,启动前确保数据目录存在。
	 * 默认 ../data(相对 backend 工作目录 = 项目根 data/),可用环境变量 DATA_DIR 覆盖。
	 * 同时将数据库切换为 WAL 日志模式(读写不互斥),显著降低并发时的 SQLITE_BUSY。
	 */
	private static void ensureDataDir() {
		String dataDir = System.getenv().getOrDefault("DATA_DIR", "../data");
		try {
			Path dir = Path.of(dataDir);
			Files.createDirectories(dir);
			Path db = dir.resolve("codeoffer.db");
			try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
				try (Statement st = conn.createStatement()) {
					st.execute("PRAGMA journal_mode=WAL"); // 持久设置,之后所有连接均为 WAL
				}
			}
		} catch (Exception e) {
			throw new IllegalStateException("无法初始化数据目录: " + dataDir, e);
		}
	}

}
