# Cannsheet Backend Analytics v1 — Rollback

Date: 2026-07-18

Analytics is read-only, so a normal rollback requires code/deployment changes
only. Do not restore a spreadsheet backup unless an independent data problem is
confirmed.

## Production rollback

Production project:
`1C_I7_vWIuZoxQN3ZR3iAcNWq0-X3aJj4cS1EHbk2nW6yJT2dVfgy3vA2`

Active deployment ID:
`AKfycbys-9r8PnkcTwUwbWL4hITr73n3nF240WQ1Vz6PW_V2XBwzusnMU3Br8tLaCgTiFz7hmQ`

Current analytics version: Apps Script version `9`
Immediate rollback version: Apps Script version `8`

Perform both parts because the web app uses a numbered deployment while the
spreadsheet triggers execute HEAD.

1. In the production Apps Script project, restore `Code.gs` to the pre-analytics
   source from Git commit `fd3e34f`:

   ```powershell
   git show fd3e34f:backend_additions.gs
   ```

   Replace `Code.gs` with that complete output and save. This rolls back the two
   HEAD triggers.

2. Open **Deploy → Manage deployments**.
3. Select the active deployment and click **Edit**.
4. Change **Version** from `9` to `8`.
5. Leave execution identity and access unchanged.
6. Deploy the update. Confirm the deployment ID and `/exec` URL did not change.
7. Verify a bare GET returns `environment: PRODUCTION`, `apiVersion: 2`, and a
   products array.
8. Verify there are exactly two HEAD triggers:
   `onInventoryEdit` and `onFormSubmit`.
9. Confirm analytics requests no longer return an analytics resource response.

No Android rollback is required because the existing Android app does not call
the new resources yet.

Production pre-promotion spreadsheet backup:
`https://docs.google.com/spreadsheets/d/1tsnlLNuhCyYSGoMPLc_uso4ekmW6z3ID8scs7LkFWdo/edit`

Use this backup only after comparing the live sheet and proving an unexpected
mutation occurred. The analytics code itself never writes.

## Sandbox rollback

Sandbox project:
`14GdK-_WOr3lFwU9Xmx3OuvhzWKljPYKFH5L7MRCaC0dXsOOHG9LJQ-_o`

Active deployment ID:
`AKfycbxHPo_Zet7ctELj-rDl4iZSbDwAOxOWOHHsmVUVyteKzZbrjNDVI3GOKhJN_IX69AinuA`

Current analytics version: Apps Script version `12`
Immediate rollback version: Apps Script version `10`

1. Restore sandbox `Code.gs` from Git commit `fd3e34f` and save it so HEAD
   triggers use the old backend.
2. Edit the active deployment in place and select version `10`.
3. Keep the deployment ID, URL, execution identity, and access unchanged.
4. Run `resetSandboxData()` from `sandbox_provisioning.gs`.
5. Verify the normal baseline has six products and five canonical events.
6. Verify exactly two HEAD triggers remain installed.

Sandbox pre-validation spreadsheet backup:
`https://docs.google.com/spreadsheets/d/1rLRE05tpIxAZ0wPVre2cp6LwW9On9ejY16KI3acGpg4/edit`

## Forward recovery

If rollback is performed only to investigate an external or transient issue:

1. keep version `9` (production) and version `12` (sandbox) available;
2. diagnose against the saved source, evidence, and backups;
3. rerun the full local analytics suite;
4. repeat the 400/3,600 sandbox fixture and no-write assertion;
5. promote the tested numbered version in place only after all gates pass.
