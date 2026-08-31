# License and Asset Registry

**Status:** Packaged audio deferred by product owner
**Owner:** Unassigned

The product owner confirmed on 2026-08-29 that all required licenses have been received. No licensed sound, model, custom font, illustration, or production icon has yet been imported into the production application. The repository and inspected local Downloads directory contained no audio binary as of 2026-08-31, so checksum/source fields cannot yet be completed.

Before an asset is committed, add one row with its actual file checksum and attach the license evidence in the organization-controlled archive. License files containing private commercial terms must not be committed unless approved.

| Asset/dependency | Version/file | Source/vendor | Permitted use | Attribution/notice | SHA-256 | Evidence location | Status |
|---|---|---|---|---|---|---|---|
| Production alarm sound (`classic`) | Expected `mobile/android/app/src/main/assets/alarms/classic.ogg` | Pending actual source/vendor | Pending actual grant terms | Pending actual notice terms | Pending binary | Pending evidence path | Deferred 2026-08-31; system-tone fallback active |
| App icon/illustrations | Pending | Pending | Pending | Pending | Pending | Pending | Not imported |
| Push-up model/runtime assets | Pending | Pending | Pending | Pending | Pending | Pending | Not imported |
| npm/Gradle open-source dependencies | Lockfile/build-resolved | Package registries | Per upstream license | Notice bundle required | Generated at release | SBOM/notice job pending | In use |
