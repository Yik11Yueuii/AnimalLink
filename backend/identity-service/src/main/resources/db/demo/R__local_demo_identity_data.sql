INSERT INTO `user` (id, display_name, account_status, system_role)
VALUES
  ('00000000-0000-0000-0000-000000000001', '本地示例用户', 'ACTIVE', 'USER'),
  ('00000000-0000-0000-0000-000000000002', '本地治理管理员', 'ACTIVE', 'GOVERNANCE_ADMIN')
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

INSERT INTO campus_membership
  (id, user_id, campus_id, membership_type, status, approved_at, last_verification_id)
VALUES
  ('12000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   '10000000-0000-0000-0000-000000000001', 'STUDENT', 'ACTIVE', CURRENT_TIMESTAMP(6),
   '11000000-0000-0000-0000-000000000001') AS incoming
ON DUPLICATE KEY UPDATE membership_type = incoming.membership_type, status = incoming.status,
  approved_at = incoming.approved_at, last_verification_id = incoming.last_verification_id;
