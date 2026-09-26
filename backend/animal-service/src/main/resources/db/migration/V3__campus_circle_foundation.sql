CREATE TABLE post (
  id CHAR(36) NOT NULL,
  campus_id CHAR(36) NOT NULL,
  animal_id CHAR(36) NULL,
  author_user_id CHAR(36) NOT NULL,
  post_type VARCHAR(32) NOT NULL DEFAULT 'CAMPUS_POST',
  text_content VARCHAR(2000) NOT NULL,
  visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_post_campus_status_created (campus_id, status, visibility, created_at DESC, id DESC),
  KEY idx_post_animal_created (animal_id, created_at DESC),
  KEY idx_post_author_created (author_user_id, created_at DESC),
  CONSTRAINT fk_post_animal FOREIGN KEY (animal_id) REFERENCES animal (id),
  CONSTRAINT chk_post_type CHECK (post_type IN ('CAMPUS_POST', 'RESCUE_UPDATE', 'ADOPTION', 'POST_ADOPTION')),
  CONSTRAINT chk_post_visibility CHECK (visibility IN ('PUBLIC')),
  CONSTRAINT chk_post_status CHECK (status IN ('ACTIVE', 'HIDDEN'))
) ENGINE=InnoDB;

CREATE TABLE post_media (
  id CHAR(36) NOT NULL,
  post_id CHAR(36) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  content_type VARCHAR(120) NOT NULL,
  media_type VARCHAR(32) NOT NULL,
  size_bytes BIGINT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_post_media_object_key (object_key),
  KEY idx_post_media_post_order (post_id, sort_order, id),
  CONSTRAINT fk_post_media_post FOREIGN KEY (post_id) REFERENCES post (id),
  CONSTRAINT chk_post_media_type CHECK (media_type IN ('IMAGE', 'VIDEO')),
  CONSTRAINT chk_post_media_visibility CHECK (visibility IN ('PUBLIC', 'RESTRICTED')),
  CONSTRAINT chk_post_media_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
  CONSTRAINT chk_post_media_sort CHECK (sort_order >= 0)
) ENGINE=InnoDB;

CREATE TABLE comment (
  id CHAR(36) NOT NULL,
  post_id CHAR(36) NOT NULL,
  author_user_id CHAR(36) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_comment_post_status_created (post_id, status, created_at ASC, id ASC),
  KEY idx_comment_author_created (author_user_id, created_at DESC),
  CONSTRAINT fk_comment_post FOREIGN KEY (post_id) REFERENCES post (id),
  CONSTRAINT chk_comment_status CHECK (status IN ('ACTIVE', 'HIDDEN'))
) ENGINE=InnoDB;

CREATE TABLE post_like (
  user_id CHAR(36) NOT NULL,
  post_id CHAR(36) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id, post_id),
  KEY idx_post_like_post_created (post_id, created_at DESC),
  CONSTRAINT fk_post_like_post FOREIGN KEY (post_id) REFERENCES post (id)
) ENGINE=InnoDB;

CREATE TABLE animal_follow (
  user_id CHAR(36) NOT NULL,
  animal_id CHAR(36) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (user_id, animal_id),
  KEY idx_animal_follow_animal_created (animal_id, created_at DESC),
  KEY idx_animal_follow_user_created (user_id, created_at DESC, animal_id),
  CONSTRAINT fk_animal_follow_animal FOREIGN KEY (animal_id) REFERENCES animal (id)
) ENGINE=InnoDB;
