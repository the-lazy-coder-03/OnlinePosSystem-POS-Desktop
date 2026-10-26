# Stage 4 isolated export and local import

Use JDK 25, PostgreSQL 16, and the packaged backend. Never connect this tool to production. Obtain an
approved restored snapshot and verify its provenance, source revision, schema and branch IDs first.
Restore it on loopback under `pos_catalogue_snapshot`; use a dedicated role with SELECT on the 31 catalogue
tables only. Keep passwords outside source, command arguments and logs. A cloud source mismatch needs a
reviewed new contract/mapping, not changing a revision label to bypass validation.

Set `POS_SNAPSHOT_DB_URL=jdbc:postgresql://127.0.0.1:<port>/pos_catalogue_snapshot`,
`POS_SNAPSHOT_DB_USER`, `POS_SNAPSHOT_DB_PASSWORD`, and
`POS_SNAPSHOT_SOURCE_REVISION=ad708b93e1026605f15e39dc02ebce790948ba0a` in the process environment.
The last variable is an explicit operator attestation, not independent database provenance verification.

```sh
java -jar store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar --catalogue-export /safe/path/kenridge.json 1
java -jar store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar --catalogue-export /safe/path/uitzicht.json 2
```

Use equivalent Windows paths in PowerShell. Output paths must not exist. Retain the printed SHA-256 for
review outside the import request; review table counts, prices, seasonal/active flags, recipe links,
pizza size rules, special days/date ranges and customization rules with the branch manager. Export files
are catalogue data only but should remain in a controlled location. No export endpoint is added to cloud.

Upgrade the local installation with the protected owner migration flow from Stage 3. Bootstrap the local
manager and enroll the importing terminal first. The branch must have no existing catalogue/prices/drafts.
Login with the enrolled terminal certificate, then send a JSON body to:

```text
POST https://<trusted-master>/api/local/branches/1/catalogue/bootstrap
Authorization: Bearer <terminal-bound-local-manager-token>
Content-Type: application/json

{"commandId":"<new UUID retained for retries>","expectedSha256":"<reviewed printed hash>","snapshot":<exported JSON object>}
```

Use the installed CA, terminal certificate and key; never disable TLS verification. Supply bearer tokens
through a protected client, not shared shell history. The import endpoint follows the same trust/login
contract as Stage 3. It requires Content-Length and refuses bodies over 16 MiB. The explicit loopback
`local-store` development profile remains available only for disposable tests.

Success includes the snapshot identity, hash, counts and LOCAL price-authority marker. Save the reviewed
manifest and response. GET the branch catalogue to check persisted source/effective prices and cutover
record. Disconnect internet and verify menu/catalogue reads still work over the trusted LAN. Edit one price
as manager and verify its revision changes; retry the original import and confirm that price remains.
Try a cashier, a foreign branch and a changed snapshot: all must fail without altering data.
Run equivalent isolated installations for both branches. Compare cloud fixture pricing/selection results,
including toppings, default ingredients, base choices, burgers, specials and dates, before live approval.
The import preserves these definitions; full selection/quote processing belongs to Stage 5.

Local completion of an import establishes the local baseline for that isolated installation only.
It does not switch website price ownership, publish local prices, enable sales, migrate cloud, or approve
live handover. Cloud synchronization remains disabled until its later approved stages.

For import failure, inspect redacted diagnostics, resolve the input/database issue and retry the original
UUID and bytes. Do not delete source records or modify applied migration files. Preserve a verified backup
before binary changes; retain the additive schema and history on rollback. See Stage 4 evidence for the
older-binary migration-validation limitation and outstanding release gates.

## Testing an external local snapshot

After the isolated export, place branch files in a controlled directory using names
`cloud-seed-branch1.json` and `cloud-seed-branch2.json` (the names also support existing seed fixtures).
Set `POS_TEST_CATALOGUE_SNAPSHOT_DIR` to this directory along with the disposable PostgreSQL test
configuration described in Stage 3. Run the full Maven install. The branch round-trip test reads these
files instead of checked-in seeds, validates their manifests, imports each branch independently, and
compares every source record and every price baseline. All test writes target `pos_stage3_test`; this
variable is a file directory, never a source database connection. Unset it to return to CI seed fixtures.
Never copy environment files or local snapshots into the repository. After recording aggregate evidence,
stop and delete the disposable cluster and securely remove temporary credentials/snapshots.
