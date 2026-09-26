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
