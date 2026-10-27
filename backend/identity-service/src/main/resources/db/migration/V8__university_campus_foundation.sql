CREATE TABLE university (
  id CHAR(36) NOT NULL,
  moe_institution_code VARCHAR(20) NOT NULL,
  official_name VARCHAR(120) NOT NULL,
  short_name VARCHAR(60) NULL,
  province VARCHAR(80) NOT NULL,
  city VARCHAR(80) NOT NULL,
  status VARCHAR(32) NOT NULL,
  source_name VARCHAR(160) NOT NULL,
  source_url VARCHAR(500) NOT NULL,
  source_updated_at DATE NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_university_moe_code (moe_institution_code),
  UNIQUE KEY uk_university_official_name (official_name),
  KEY idx_university_status_province_name (status, province, official_name),
  KEY idx_university_status_city_name (status, city, official_name),
  CONSTRAINT chk_university_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE campus
  ADD COLUMN university_id CHAR(36) NULL AFTER id,
  ADD COLUMN campus_type VARCHAR(32) NOT NULL DEFAULT 'LEGACY' AFTER region,
  ADD COLUMN address VARCHAR(255) NULL AFTER campus_type,
  ADD COLUMN source_url VARCHAR(500) NULL AFTER address,
  ADD COLUMN source_updated_at DATE NULL AFTER source_url,
  ADD COLUMN university_scope_key VARCHAR(160) GENERATED ALWAYS AS (
    CASE
      WHEN university_id IS NULL THEN CONCAT('LEGACY:', city)
      ELSE university_id
    END
  ) STORED AFTER source_updated_at,
  ADD COLUMN community_university_key CHAR(36) GENERATED ALWAYS AS (
    CASE
      WHEN campus_type = 'UNIVERSITY_COMMUNITY' THEN university_id
      ELSE NULL
    END
  ) STORED AFTER university_scope_key,
  ADD KEY idx_campus_university_status_name (university_id, status, name),
  ADD UNIQUE KEY uk_campus_one_community_per_university (community_university_key),
  ADD CONSTRAINT fk_campus_university FOREIGN KEY (university_id) REFERENCES university (id),
  ADD CONSTRAINT chk_campus_type CHECK (
    campus_type IN ('LEGACY', 'UNIVERSITY_COMMUNITY', 'CAMPUS_AREA', 'ADMINISTRATIVE_CAMPUS')
  ),
  ADD CONSTRAINT chk_campus_university_scope CHECK (
    (campus_type = 'LEGACY' AND university_id IS NULL)
    OR (campus_type <> 'LEGACY' AND university_id IS NOT NULL)
  );

ALTER TABLE campus DROP INDEX uk_campus_name_city;
ALTER TABLE campus ADD UNIQUE KEY uk_campus_university_name (university_scope_key, name);

-- Curated Guangdong university master data. The source is the Ministry of
-- Education list published on 2026-06-18. A row here does not imply that an
-- AnimalLink community has been opened for that university.
INSERT INTO university
  (id, moe_institution_code, official_name, short_name, province, city, status,
   source_name, source_url, source_updated_at)
VALUES
  ('21000000-0000-4000-8000-000000001001', '4144010558', '中山大学', NULL, '广东省', '广州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001002', '4144010559', '暨南大学', NULL, '广东省', '广州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001003', '4144010560', '汕头大学', NULL, '广东省', '汕头市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001004', '4144010561', '华南理工大学', NULL, '广东省', '广州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001005', '4144010564', '华南农业大学', NULL, '广东省', '广州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001006', '4144010566', '广东海洋大学', NULL, '广东省', '湛江市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001007', '4144010574', '华南师范大学', NULL, '广东省', '广州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001008', '4144010576', '韶关学院', NULL, '广东省', '韶关市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001009', '4144010577', '惠州学院', NULL, '广东省', '惠州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001010', '4144010578', '韩山师范学院', NULL, '广东省', '潮州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001011', '4144010580', '肇庆学院', NULL, '广东省', '肇庆市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001012', '4144010582', '嘉应学院', NULL, '广东省', '梅州市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001013', '4144010590', '深圳大学', NULL, '广东省', '深圳市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001014', '4144011349', '五邑大学', NULL, '广东省', '江门市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001015', '4144011819', '东莞理工学院', NULL, '广东省', '东莞市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18'),
  ('21000000-0000-4000-8000-000000001016', '4144011847', '佛山大学', NULL, '广东省', '佛山市', 'ACTIVE', '教育部2026年全国普通高等学校名单', 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18');

-- AnimalLink currently opens one school-level community for Hanshan Normal
-- University. This is a logical business scope, not a claim that the school
-- has one physical campus. The west, central, south and east area names from
-- the admissions charter remain optional future location descriptions and do
-- not receive independent campus IDs or communities.
INSERT INTO campus
  (id, university_id, name, short_name, city, region, campus_type, address,
   source_url, source_updated_at, status)
VALUES
  ('22000000-0000-4000-8000-000000000001', '21000000-0000-4000-8000-000000001010', '韩山师范学院社区', '韩师社区', '潮州市', NULL, 'UNIVERSITY_COMMUNITY', NULL, 'https://www.moe.gov.cn/jyb_xxgk/s5743/s5744/202606/t20260618_1441074.html', '2026-06-18', 'ACTIVE');
