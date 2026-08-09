package com.codeoffer.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
	 */
	private static void ensureDataDir() {
		String dataDir = System.getenv().getOrDefault("DATA_DIR", "../data");
		try {
			Files.createDirectories(Path.of(dataDir));
		} catch (IOException e) {
			throw new IllegalStateException("无法创建数据目录: " + dataDir, e);
		}
	}

}
