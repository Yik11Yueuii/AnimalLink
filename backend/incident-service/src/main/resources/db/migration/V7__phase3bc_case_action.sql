CREATE TABLE case_action (
 id varchar(36) NOT NULL PRIMARY KEY,
 case_id varchar(36) NOT NULL,
 actor_user_id varchar(36) NOT NULL,
 description varchar(2000) NOT NULL,
 occurred_at timestamp(6) NOT NULL,
 created_at timestamp(6) NOT NULL,
 CONSTRAINT fk_case_action_case FOREIGN KEY (case_id) REFERENCES animal_case(id),
 KEY ix_case_action_case_occurred_id(case_id,occurred_at,id),
 KEY ix_case_action_actor_created(actor_user_id,created_at)
);
