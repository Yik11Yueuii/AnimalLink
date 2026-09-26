# Nacos Config local verification

The Phase 0.1 probe uses Data ID `identity-service.yaml`, group `DEFAULT_GROUP`, and the public namespace (empty namespace ID). The `identity-service` imports this Data ID optionally from `application.yaml`, regardless of whether its Spring profile is `local` or `dev`.

Publish the harmless example in `config-examples/identity-service.yaml` through the Nacos console at `http://localhost:8848/nacos`. Select the public namespace, create a YAML configuration with the Data ID and group above, and paste the example content. Do not put credentials or personal data in this configuration.

Start `identity-service` and request `http://localhost:8081/actuator/info`. A successful Config read returns `{"animallink":{"environment":"nacos-local"}}`. With Nacos Config absent, the service can still start using its local application configuration, and the same endpoint reports `local-fallback`.

This probe is diagnostic only. It does not create business data or make Nacos Config a hard startup dependency.
