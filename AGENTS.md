# AnimalLink Project AGENTS.md

## 1. Source of truth

Use this order when requirements conflict:
1. Latest explicitly approved PRD / PRD Amendment
2. Latest explicitly approved Technical Design / TD Amendment
3. This AGENTS.md
4. Existing implementation
5. Deprecated or older documents

Current baseline:
- Product: AnimalLink PRD V2.1
- Technical Design: AnimalLink Technical Design V1.0

Deprecated: PRD V1.x, old broad multi-scenario scope, deleted prototype, old modular-monolith architecture.
Do not use deprecated material as an implementation source.
If a task conflicts with PRD/TD, report the conflict before changing scope or architecture.

## 2. Product invariants

- `Animal` is the long-lived core entity.
- Adoption never creates a second Animal identity.
- Campus Circle is the main observation/community surface.
- `Event != Case`.
- Event = what happened; Case = how it is formally handled.
- Event may have `animalId = null`; UNKNOWN is valid.
- `EventDraft` is not a formal Event state.
- AI creates drafts/candidates; humans confirm; Java services execute formal state changes.
- Candidate Matching returns Top-K only; never auto-bind or auto-merge Animal.
- `APPROVED != ADOPTED`.
- `AdoptionRelation` is created only after completed handover.
- Campus Context, CampusMembership, VolunteerMembership, AdoptionRelation are distinct.
- User capabilities may stack; do not implement role-switching modes.
- Public support/fundraising must be attached to a concrete Case.

## 3. Client baseline

Mini Program navigation:
- Campus Circle
- Animals
- Rescue
- My

Campus Circle has a floating `+` for:
- record animal activity
- report abnormal situation / request help

The floating `+` is not a Tab.

Governance Web:
- Vue 3
- TypeScript
- Vite
- Element Plus

Governance Web is for review, governance, correction, auditing, and supervision. It does not replace students or volunteers in normal workflows.

## 4. Backend architecture

Use:
- Java 21
- Spring Boot 3.x
- Spring Cloud Alibaba
- Nacos
- API Gateway

Approved services:
- `identity-service`
- `animal-service`
- `incident-service`
- `adoption-service`
- `intelligence-service`

Do not add, split, or merge services without an approved TD change.

Ownership:
- identity: User, Campus, CampusMembership, CampusVerification, VolunteerMembership, auth/permission data
- animal: Animal, media/profile, Post/Comment/Like/Follow, TimelineEntry, AnimalMergeRecord
- incident: Event/EventDraft, Evidence, Case/Participant/Action, SupportCampaign/Contribution/Expense/Settlement
- adoption: Listing, Application/Review, Handover, AdoptionRelation, FollowUp
- intelligence: multimodal parsing, feature extraction, Candidate Matching, duplicate-event detection, credential precheck, AI task/result/embedding/experiment data

Historical RAG is optional enhancement work and must not block MVP delivery.

## 5. Data and storage

Primary business database: MySQL 8.x.
Preferred local deployment: one MySQL instance, separate logical database per service.

Rules:
- each service owns its tables
- never cross-service SQL JOIN
- never directly write another service's tables
- cross-service references use IDs plus APIs or domain events

Use MinIO for images, video, credentials, adoption material, and support/expense evidence.
MySQL stores metadata/object references, not large binary objects.

## 6. AI rules

Principle: AI understands reality; humans confirm; Java executes reality.

Multimodal parsing may assist with species, coat color, visible traits, posture/state, and structured extraction of user-described abnormalities.
It must not diagnose disease, prescribe treatment, or write uncertain AI output directly as business fact.
AI failure must have a manual fallback.

Candidate Matching:
1. recall by campus/species/area/basic traits
2. rank by image embedding + structured traits + geo + time/history

Always support:
- choose candidate
- none of these
- not sure / UNKNOWN

Keep the pipeline experimentable for image-only vs multi-source comparisons.

## 7. Event / Case / support

An Event does not automatically create a Case.
Typical flow: `EventDraft -> human confirmation -> Event -> volunteer handling -> Case`.

V1:
- Case must originate from Event
- one Event has at most one primary Case

Students/alumni may submit Events and add Evidence.
Authorized volunteers may claim Case, participate, add CaseAction, submit results, and request Case-linked support.
Case claiming must be concurrency-safe.
Use CaseAction for operational detail; do not create an oversized status machine.
Support must be tied to a concrete Case; do not build a generic fundraising platform.

## 8. Adoption

- multiple users may apply for one Animal
- do not use first-come-first-served by default
- only completed handover creates ACTIVE AdoptionRelation
- do not erase adoption history; end relations through lifecycle state
- adopter does not gain CampusMembership by adopting
- CommunityPost and AdoptionFollowUp are different records

## 9. Authorization and privacy

Authorization may depend on:
- SystemRole
- CampusMembership
- VolunteerMembership
- AdoptionRelation
- CaseParticipation
- current Campus Context
- resource visibility

Volunteer rights are Campus-scoped.
Sensitive location is protected server-side: public response = approximate; authorized relevant user = precise when needed.
Never rely only on frontend hiding for sensitive coordinates or credentials.

## 10. Consistency and messaging

Within one service: MySQL local transaction + constraints/conditional updates.
Across services: prefer eventual consistency; avoid distributed transactions.

RabbitMQ + Transactional Outbox are used only for real cross-service domain events such as `CaseResolved` and `AdoptionCompleted`.
Consumers must be idempotent.
Do not route every operation through MQ.
If the current Phase does not require RabbitMQ, do not introduce it early.

## 11. Technology guardrails

Do not introduce without explicit approval:
- Kubernetes / Service Mesh
- Seata / distributed transaction frameworks
- Kafka / Flink / Elasticsearch
- extra microservices
- full GIS platform
- generic Agent / multi-agent framework
- pet-health management
- mall/e-commerce
- IM/chat
- gamification/leaderboards
- generic pet AI chat

Redis is not a default dependency; add it only for a demonstrated need.

## 12. Development phases

Phase 0: service/repository skeleton, build conventions, Gateway, Nacos, local MySQL/MinIO, migrations, health checks, auth foundation.
Phase 1: User/Campus/CampusMembership, Animal, Campus Circle basic read flows.
Phase 2: media upload, animal recording, multimodal parser, Candidate Matching, human confirmation/fallback.
Phase 3: Event, Evidence, VolunteerMembership, Case/CaseAction, rescue timeline.
Phase 4: adoption application/review/handover, AdoptionRelation, post-adoption updates.
Phase 5: support records, credential AI precheck, Animal merge/governance, FollowUp/content governance.
Phase 6: reliable cross-service events, Candidate Matching experiments, performance/reliability hardening, optional Historical RAG.

Do not implement the whole project in one task.

## 13. Implementation discipline

At the start of each Codex task:
- identify the PRD/TD section
- state the owning service
- list affected entities/APIs/tables
- avoid unrelated refactors

Separate interface/API, application orchestration, domain logic, and infrastructure concerns.
State transitions belong in backend logic, not frontend code.
Use migrations for schema changes.
Add tests for state transitions, authorization, concurrent Case claiming, AI fallback, and idempotent event consumption when messaging exists.

## 14. Change control

Do not silently change product scope, navigation, service boundaries, data ownership, domain meanings, state machines, permission semantics, AI responsibility, or MVP boundaries.

If a real design issue appears, report:
1. issue
2. impact
3. proposed change
4. affected PRD/TD sections

Wait for approval before architecture-level deviation.
