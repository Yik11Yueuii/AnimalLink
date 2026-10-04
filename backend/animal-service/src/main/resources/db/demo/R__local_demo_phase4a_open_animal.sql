INSERT INTO animal
  (id, campus_id, display_name, species, sex, coat_color, distinctive_features,
   description, sterilization_status, typical_area, identity_status, adoption_status,
   current_context, version)
VALUES
  ('20000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001',
   'Phase4A Open Adoption Animal', 'CAT', 'UNKNOWN', '灰白', '本地领养流程验收专用',
   '仅供本地开发环境 Phase 4A Gateway smoke 使用的合成 Animal。', 'UNKNOWN', '本地开发验证区域',
   'ACTIVE', 'OPEN', 'CAMPUS', 0) AS incoming
ON DUPLICATE KEY UPDATE
  campus_id = incoming.campus_id, display_name = incoming.display_name, species = incoming.species,
  sex = incoming.sex, coat_color = incoming.coat_color,
  distinctive_features = incoming.distinctive_features, description = incoming.description,
  sterilization_status = incoming.sterilization_status, typical_area = incoming.typical_area,
  identity_status = incoming.identity_status, adoption_status = incoming.adoption_status,
  current_context = incoming.current_context, version = incoming.version;
