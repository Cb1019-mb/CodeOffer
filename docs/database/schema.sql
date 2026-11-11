-- CodeOffer MySQL 建表语句（由迁移脚本从运行库导出）
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `project`;
CREATE TABLE `project` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `description` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `health_report` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `repo_dir` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `repo_info` mediumtext COLLATE utf8mb4_unicode_ci,
  `repo_url` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ANALYZING','CLONING','DONE','FAILED','GENERATING','PENDING','REVIEW') COLLATE utf8mb4_unicode_ci NOT NULL,
  `tech_stack` varchar(4000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=607 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `gen_task`;
CREATE TABLE `gen_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `error_msg` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `progress` int NOT NULL,
  `project_id` bigint DEFAULT NULL,
  `status` enum('DONE','FAILED','RUNNING') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` enum('FULL_GENERATION','REGENERATE_CARD') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=911 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `knowledge_card`;
CREATE TABLE `knowledge_card` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ai_draft` text COLLATE utf8mb4_unicode_ci,
  `content` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `edited` bit(1) NOT NULL,
  `last_reviewed` datetime(6) DEFAULT NULL,
  `mastery` int NOT NULL,
  `next_review` datetime(6) DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  `title` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `type` enum('ARCHITECTURE','COMPARE_PRACTICE','DIFFICULTY','FLOW_DIAGRAM','HIGHLIGHT','RUN_GUIDE','TAKEAWAY') COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_input` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=475 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `resume_entry`;
CREATE TABLE `resume_entry` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ai_draft` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime(6) DEFAULT NULL,
  `edited` bit(1) NOT NULL,
  `interview_qas` text COLLATE utf8mb4_unicode_ci,
  `project_id` bigint DEFAULT NULL,
  `star_content` varchar(10000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=205 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
