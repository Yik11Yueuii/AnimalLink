CREATE TABLE case_action_media (
 id varchar(36) NOT NULL PRIMARY KEY,
 case_action_id varchar(36) NOT NULL,
 object_key varchar(512) NOT NULL,
 content_type varchar(128) NOT NULL,
 size_bytes bigint NOT NULL,
 sort_order int NOT NULL,
 created_at timestamp(6) NOT NULL,
 CONSTRAINT fk_case_action_media_action FOREIGN KEY (case_action_id) REFERENCES case_action(id),
 KEY ix_case_action_media_action_sort_id(case_action_id,sort_order,id)
);
