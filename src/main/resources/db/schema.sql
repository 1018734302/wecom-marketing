-- 企微营销自动化系统 阶段一 建表脚本
-- 执行方式：mysql -u root -p < schema.sql
-- 库名：wecom_marketing

CREATE DATABASE IF NOT EXISTS wecom_marketing
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE wecom_marketing;

-- 客户档案
CREATE TABLE IF NOT EXISTS ct_customer (
  id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
  external_uid VARCHAR(64)  NOT NULL COMMENT '企微 external_userid',
  name         VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '客户昵称',
  avatar       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像地址',
  follow_user  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '归属成员 userid',
  add_way      INT          NOT NULL DEFAULT 0 COMMENT '企微添加方式',
  source       VARCHAR(32)  NOT NULL DEFAULT 'unknown' COMMENT '渠道来源',
  status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active/deleted',
  add_time     DATETIME     NOT NULL COMMENT '添加时间',
  welcomed_at  DATETIME     NULL COMMENT '欢迎语发送成功时间，非空即不再发送',
  last_active  DATETIME     NULL COMMENT '最近一次互动时间',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ext (external_uid),
  KEY idx_status (status),
  KEY idx_source (source),
  KEY idx_add_time (add_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户档案';

-- 标签定义
CREATE TABLE IF NOT EXISTS ct_tag (
  id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
  name       VARCHAR(64) NOT NULL COMMENT '标签名',
  corp_tag_id VARCHAR(64) NULL COMMENT '企微企业标签 id',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签定义';

-- 客户-标签关联
CREATE TABLE IF NOT EXISTS ct_customer_tag (
  id          BIGINT   PRIMARY KEY AUTO_INCREMENT,
  customer_id BIGINT   NOT NULL,
  tag_id      BIGINT   NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_customer_tag (customer_id, tag_id),
  KEY idx_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户标签关联';

-- 事件流水（回调幂等的依据）
CREATE TABLE IF NOT EXISTS ev_event (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  event_key   VARCHAR(160) NOT NULL COMMENT '幂等键，重复投递时唯一约束拦截',
  type        VARCHAR(64)  NOT NULL COMMENT '事件类型',
  payload     TEXT         NULL COMMENT '原始报文',
  occurred_at DATETIME     NOT NULL COMMENT '事件发生时间',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_event_key (event_key),
  KEY idx_type (type),
  KEY idx_occurred (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件流水';
