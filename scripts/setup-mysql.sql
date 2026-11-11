-- CodeOffer 专用数据库与账号（密码请替换为强密码后执行）
-- 执行: "D:\Users\mysql8\bin\mysql.exe" -u root -p < scripts/setup-mysql.sql
CREATE DATABASE IF NOT EXISTS codeoffer CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'codeoffer_app'@'localhost' IDENTIFIED BY '<替换为密码>';
-- 账号已存在需轮换密码时，改用: ALTER USER 'codeoffer_app'@'localhost' IDENTIFIED BY '<替换为密码>';
GRANT ALL PRIVILEGES ON codeoffer.* TO 'codeoffer_app'@'localhost';
FLUSH PRIVILEGES;
