INSERT INTO post
  (id, campus_id, animal_id, author_user_id, post_type, text_content, visibility, status, created_at)
VALUES
  ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
   '20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   'CAMPUS_POST', '今天傍晚在教学楼附近看到小橘晒太阳。', 'PUBLIC', 'ACTIVE', '2026-09-25 17:30:00.000000'),
  ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
   '20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002',
   'CAMPUS_POST', '阿黄今天在体育场边散步，状态很好。', 'PUBLIC', 'ACTIVE', '2026-09-25 18:00:00.000000') AS incoming
ON DUPLICATE KEY UPDATE campus_id = incoming.campus_id, animal_id = incoming.animal_id,
  author_user_id = incoming.author_user_id, text_content = incoming.text_content,
  visibility = incoming.visibility, status = incoming.status;

INSERT INTO comment (id, post_id, author_user_id, content, status, created_at)
VALUES ('31000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000001', '它最近经常在这里出现。', 'ACTIVE',
        '2026-09-25 18:10:00.000000') AS incoming
ON DUPLICATE KEY UPDATE content = incoming.content, status = incoming.status;

INSERT INTO post_like (user_id, post_id)
VALUES ('00000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001')
ON DUPLICATE KEY UPDATE user_id = user_id;

INSERT INTO animal_follow (user_id, animal_id)
VALUES ('00000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001')
ON DUPLICATE KEY UPDATE user_id = user_id;
