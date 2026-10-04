INSERT INTO `user` (id, display_name, account_status, system_role)
VALUES
  ('00000000-0000-0000-0000-000000000001', '本地示例用户', 'ACTIVE', 'USER'),
  ('00000000-0000-0000-0000-000000000002', '本地治理管理员', 'ACTIVE', 'GOVERNANCE_ADMIN'),
  ('00000000-0000-0000-0000-000000000011', '本地烟雾报告者', 'ACTIVE', 'USER'),
  ('00000000-0000-0000-0000-000000000012', '本地烟雾证据提交者', 'ACTIVE', 'USER'),
  ('00000000-0000-0000-0000-000000000013', '本地烟雾志愿者', 'ACTIVE', 'USER'),
  ('00000000-0000-0000-0000-000000000014', '本地烟雾无关用户', 'ACTIVE', 'USER')
ON DUPLICATE KEY UPDATE
  display_name = VALUES(display_name),
  account_status = VALUES(account_status),
  system_role = VALUES(system_role);

INSERT INTO campus (id, name, short_name, city, region, status)
VALUES
  ('10000000-0000-0000-0000-000000000001', '示例大学东校区', '示例东校区', '示例市', '东区', 'ACTIVE'),
  ('10000000-0000-0000-0000-000000000002', '示例大学西校区', '示例西校区', '示例市', '西区', 'ACTIVE'),
  ('10000000-0000-0000-0000-000000000003', '演示学院', '演示学院', '演示市', NULL, 'ACTIVE')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  short_name = VALUES(short_name),
  city = VALUES(city),
  region = VALUES(region),
  status = VALUES(status);

INSERT INTO campus_verification
  (id, user_id, campus_id, requested_membership_type, applicant_name, affiliation_note,
   status, review_reason, reviewed_by, reviewed_at)
VALUES
  ('11000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', 'STUDENT', '本地示例用户', 'Phase 1C 本地演示成员',
   'APPROVED', '本地演示数据', '00000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP(6)) AS incoming
ON DUPLICATE KEY UPDATE status = incoming.status, review_reason = incoming.review_reason,
  reviewed_by = incoming.reviewed_by, reviewed_at = incoming.reviewed_at;

INSERT INTO campus_verification
  (id, user_id, campus_id, requested_membership_type, applicant_name, affiliation_note,
   status, review_reason, reviewed_by, reviewed_at)
VALUES
  ('11000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001', 'STUDENT', '本地烟雾报告者', '仅供本地 Gateway smoke', 'APPROVED', '本地 fixture', '00000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP(6)),
  ('11000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000001', 'STUDENT', '本地烟雾证据提交者', '仅供本地 Gateway smoke', 'APPROVED', '本地 fixture', '00000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP(6)),
  ('11000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000001', 'STUDENT', '本地烟雾志愿者', '仅供本地 Gateway smoke', 'APPROVED', '本地 fixture', '00000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP(6)),
  ('11000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000014', '10000000-0000-0000-0000-000000000001', 'STUDENT', '本地烟雾无关用户', '仅供本地 Gateway smoke', 'APPROVED', '本地 fixture', '00000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP(6)) AS incoming
ON DUPLICATE KEY UPDATE status = incoming.status, review_reason = incoming.review_reason,
  reviewed_by = incoming.reviewed_by, reviewed_at = incoming.reviewed_at;

INSERT INTO campus_membership
  (id, user_id, campus_id, membership_type, status, approved_at, last_verification_id)
VALUES
  ('12000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', 'STUDENT', 'ACTIVE', CURRENT_TIMESTAMP(6),
   '11000000-0000-0000-0000-000000000001') AS incoming
ON DUPLICATE KEY UPDATE membership_type = incoming.membership_type, status = incoming.status,
  approved_at = incoming.approved_at, last_verification_id = incoming.last_verification_id;

INSERT INTO campus_membership
  (id, user_id, campus_id, membership_type, status, approved_at, last_verification_id)
VALUES
  ('12000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000001', 'STUDENT', 'ACTIVE', CURRENT_TIMESTAMP(6), '11000000-0000-0000-0000-000000000011'),
  ('12000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000001', 'STUDENT', 'ACTIVE', CURRENT_TIMESTAMP(6), '11000000-0000-0000-0000-000000000012'),
  ('12000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000001', 'STUDENT', 'ACTIVE', CURRENT_TIMESTAMP(6), '11000000-0000-0000-0000-000000000013'),
  ('12000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000014', '10000000-0000-0000-0000-000000000001', 'STUDENT', 'ACTIVE', CURRENT_TIMESTAMP(6), '11000000-0000-0000-0000-000000000014') AS incoming
ON DUPLICATE KEY UPDATE membership_type = incoming.membership_type, status = incoming.status,
  approved_at = incoming.approved_at, last_verification_id = incoming.last_verification_id;

INSERT INTO volunteer_membership
  (id, user_id, campus_id, status, application_note, review_reason, reviewed_by, reviewed_at, activated_at)
VALUES
  ('13000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000001', 'ACTIVE', '仅供本地 Gateway smoke', '本地 fixture', '00000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)) AS incoming
ON DUPLICATE KEY UPDATE status = incoming.status, application_note = incoming.application_note,
  review_reason = incoming.review_reason, reviewed_by = incoming.reviewed_by,
  reviewed_at = incoming.reviewed_at, activated_at = incoming.activated_at, paused_at = NULL, ended_at = NULL;
