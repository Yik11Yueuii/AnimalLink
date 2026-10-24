# First governance administrator bootstrap

The first governance administrator is created only by the local interactive
`AdminBootstrapCommand`. The normal Identity Service startup does not load its
bootstrap services, the command forces a non-web application context, and no
HTTP controller exposes it.

Before running it, an authorized operator must configure the Identity database
connection through the normal environment variables. From a local terminal with
Java 21 and Maven, run:

```text
mvn -pl backend/identity-service "-Dspring-boot.run.main-class=org.animallink.identity.operations.AdminBootstrapCommand" spring-boot:run
```

Do not append a username, password, or authorization value to the command. The
command rejects all command-line arguments and requires a real interactive
console. It prompts for the display name and normalized administrator username,
requires an explicit confirmation phrase, and reads the password twice with
console echo disabled.

The operation succeeds only while the bootstrap control row is open and no
`GOVERNANCE_ADMIN` account exists. It creates the active `UserAccount`, assigns
the governance role, creates its Argon2id credential, records the success audit,
and closes the bootstrap gate in one database transaction. Duplicate or
unauthorized attempts are rejected and audited. A failed transaction does not
leave a partial user, external identity, or credential.

The command output contains only the new user ID. It never prints or logs the
password, password hash, or a session token.
