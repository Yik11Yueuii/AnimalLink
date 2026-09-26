INSERT INTO animal
  (id, campus_id, display_name, species, sex, coat_color, distinctive_features,
   description, sterilization_status, typical_area)
VALUES
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
   '小橘', 'CAT', 'MALE', '橘白', '左耳有小缺口', '常在教学楼附近活动的校园猫。', 'STERILIZED', '东区教学楼附近'),
  ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001',
   '墨墨', 'CAT', 'UNKNOWN', '黑色', '胸前有一小撮白毛', '性格谨慎，通常在傍晚出现。', 'UNKNOWN', '东区三食堂附近'),
  ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002',
   '阿黄', 'DOG', 'FEMALE', '浅黄色', '右前腿有白色毛', '西校区的长期校园动物。', 'STERILIZED', '西区体育场附近') AS incoming
ON DUPLICATE KEY UPDATE
  campus_id = incoming.campus_id, display_name = incoming.display_name, species = incoming.species,
  sex = incoming.sex, coat_color = incoming.coat_color,
  distinctive_features = incoming.distinctive_features, description = incoming.description,
  sterilization_status = incoming.sterilization_status, typical_area = incoming.typical_area;

INSERT INTO timeline_entry
  (id, animal_id, source_type, source_id, entry_type, title, summary, occurred_at)
VALUES
  ('21000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
   'DEMO_SEED', '22000000-0000-0000-0000-000000000001', 'PROFILE_CREATED',
   '建立校园长期档案', '经治理人员确认后建立 Animal 主档。', '2026-09-20 08:00:00.000000'),
  ('21000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000001',
   'DEMO_SEED', '22000000-0000-0000-0000-000000000002', 'PROFILE_NOTE',
   '活动区域更新', '近期主要在东区教学楼附近活动。', '2026-09-24 17:30:00.000000'),
  ('21000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003',
   'DEMO_SEED', '22000000-0000-0000-0000-000000000003', 'PROFILE_CREATED',
   '建立校园长期档案', '经治理人员确认后建立 Animal 主档。', '2026-09-22 09:00:00.000000') AS incoming
ON DUPLICATE KEY UPDATE title = incoming.title, summary = incoming.summary,
  occurred_at = incoming.occurred_at;
