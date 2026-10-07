CREATE TABLE adoption_follow_up (
 id varchar(36) NOT NULL PRIMARY KEY,
 relation_id varchar(36) NOT NULL,
 content varchar(2000) NOT NULL,
 followed_up_at timestamp(6) NOT NULL,
 created_at timestamp(6) NOT NULL,
 updated_at timestamp(6) NOT NULL,
 CONSTRAINT fk_adoption_follow_up_relation FOREIGN KEY (relation_id) REFERENCES adoption_relation(id),
 KEY ix_adoption_follow_up_relation_followed_id (relation_id, followed_up_at DESC, id DESC)
);
