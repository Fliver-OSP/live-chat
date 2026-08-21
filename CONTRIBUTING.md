# Contributing

## Build

```
mvn package
```

Java 17+ and the Paper API repository (declared in `pom.xml`) are all this
needs - no other Maven repos, no shaded dependencies. Output lands at
`target/Fliver-LiveChat-<version>.jar`.

## Scope

This plugin only talks to the backend described in
[PROTOCOL.md](PROTOCOL.md). It should never gain a direct Discord
dependency (no JDA, no webhook URLs, no bot tokens) - that's a deliberate
design boundary, not an oversight, so keep it that way in PRs.

## Pull requests

- Keep changes focused - one plugin, one concern per PR.
- If you change what the plugin sends/expects from the backend, update
  PROTOCOL.md in the same PR.
- No new runtime dependencies without a good reason (see the "no bundled
  JSON/HTTP library" note in `api/Json.java` for the kind of reasoning
  we're protecting).
