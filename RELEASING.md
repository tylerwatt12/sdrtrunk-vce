# Publishing an Alpha Release

This checklist publishes a numbered `sdrtrunk-vce` alpha from `main` with self-contained Java 25 binaries for Windows,
Linux, and macOS. It follows the asset layout used for `v0.6.2-alpha-1`.

The examples below use `0.6.2-alpha-5`. Replace it with the version being published.

## 1. Prepare the release

- [ ] Confirm that the intended changes are merged into `main`.
- [ ] Confirm that receiver-node testing is complete for the changes being released.
- [ ] Confirm that the worktree is clean and local `main` is current:

```bash
git switch main
git status --short --branch
git pull --ff-only origin main
git config core.hooksPath .githooks
```

- [ ] Set shell variables for the release:

```bash
export VERSION=0.6.2-alpha-5
export TAG="v${VERSION}"
export RELEASE_TITLE="sdrtrunk-vce 0.6.2 Alpha 5"
export RELEASE_NOTES="src/main/resources/release-notes/${VERSION}.html"
export RELEASE_NOTES_METADATA="src/main/resources/release-notes/${VERSION}.properties"
```

- [ ] Set `projectVersion` in `gradle.properties` to `$VERSION`.
- [ ] Create `$RELEASE_NOTES` as the plain-language, rich-text document bundled into the application and used as the
      GitHub release body. It must explain what was added, changed, fixed, and removed; how users may be affected; and
      any upgrade actions.
- [ ] Create `$RELEASE_NOTES_METADATA` with the exact version, display title, and `status=draft`.
- [ ] Add an unreleased `$VERSION` section to the changelog in `README.md` that agrees with the rich-text document.
- [ ] Render and review the document for normal users. Keep programming details out unless a user must act on them.
- [ ] **STOP FOR APPROVAL.** Show the exact rich-text document to the release owner. Do not commit, build, tag, create a
      GitHub draft, or publish until the owner explicitly approves it. If wording changes, show the revised document
      again.
- [ ] After approval, change only the metadata approval field from `status=draft` to `status=approved` and date the
      matching changelog section.
- [ ] Check that no credentials, receiver addresses, private operational notes, databases, recordings, or local-only files
      are included in the pending changes.
- [ ] Verify the approval marker and document structure, then review and commit the release preparation:

```bash
./gradlew verifyApprovedReleaseNotes
git diff --check
git diff
git add gradle.properties README.md RELEASING.md "$RELEASE_NOTES" "$RELEASE_NOTES_METADATA"
git commit -m "Prepare sdrtrunk-vce ${VERSION}"
```

## 2. Test the release commit

Java 25 is required. Build from the exact commit that will be tagged.

- [ ] Confirm Java and the release version:

```bash
java -version
./gradlew properties | sed -n '/^version:/p'
```

- [ ] Run the clean build and its release-package safety checks:

```bash
./gradlew verifyApprovedReleaseNotes clean build
```

- [ ] Stop if any test, build, or safety check fails. Fix the problem and restart the checklist from the preparation
      review.

## 3. Build all platform binaries

Do not run `clean` between these commands because every task writes its finished archives into `build/image`.
Cross-platform runtime tasks require network access the first time they download their target JDKs.

- [ ] Build the Windows archives:

```bash
./gradlew --no-configuration-cache runtimeZipWindows
```

- [ ] Build the Linux and portable macOS archives:

```bash
./gradlew --no-configuration-cache runtimeZipOthers
```

- [ ] On an Apple-silicon Mac, build the native macOS application archive:

```bash
./gradlew --no-configuration-cache macAppZip
```

The complete alpha should contain these seven ZIP files:

```text
build/image/sdrtrunk-vce-linux-aarch64-v${VERSION}.zip
build/image/sdrtrunk-vce-linux-x86_64-v${VERSION}.zip
build/image/sdrtrunk-vce-macos-app-aarch64-v${VERSION}.zip
build/image/sdrtrunk-vce-osx-aarch64-v${VERSION}.zip
build/image/sdrtrunk-vce-osx-x86_64-v${VERSION}.zip
build/image/sdrtrunk-vce-windows-aarch64-v${VERSION}.zip
build/image/sdrtrunk-vce-windows-x86_64-v${VERSION}.zip
```

`runtimeZipCurrent` is useful for local testing, but its generic `sdrtrunk-vce.zip` output is not one of the complete
alpha release assets.

## 4. Verify the archives and create checksums

- [ ] List only the archives for this version and confirm that there are exactly seven:

```bash
find build/image -maxdepth 1 -type f -name "sdrtrunk-vce-*-v${VERSION}.zip" -print | sort
find build/image -maxdepth 1 -type f -name "sdrtrunk-vce-*-v${VERSION}.zip" | wc -l
```

- [ ] Test every ZIP for corruption:

```bash
for archive in build/image/sdrtrunk-vce-*-v"${VERSION}".zip; do
  unzip -tq "$archive" || exit 1
done
```

- [ ] Inspect at least one archive from each operating-system family. Confirm that it contains the launcher, bundled
      runtime, application libraries, web assets, and license, and does not contain a portable `data` directory with
      local state.
- [ ] Confirm that each archive's application JAR contains
      `io/github/dsheirer/database/upgrade/P25ActivityV19ToV20Upgrade.class`. For the native macOS app this class is in
      `Contents/app/mods/sdrtrunk-vce-jpms.jar`.
- [ ] Run the packaged upgrade helper with `--help` from one classpath image and the native macOS app. Confirm both
      return exit code 0 and print usage without creating a data folder.
- [ ] Smoke-test the native macOS application and the package for each available receiver-node architecture. Use a new
      temporary install directory; do not overwrite the active receiver-node installation during release validation.
- [ ] Generate and verify the checksum file:

```bash
(
  cd build/image || exit 1
  shasum -a 256 sdrtrunk-vce-*-v"${VERSION}".zip > SHA256SUMS.txt
  shasum -a 256 -c SHA256SUMS.txt
)
```

- [ ] Confirm that `SHA256SUMS.txt` contains exactly seven entries and only the current version:

```bash
wc -l build/image/SHA256SUMS.txt
cat build/image/SHA256SUMS.txt
```

## 5. Tag the verified commit

- [ ] Confirm that the worktree is clean and record the release commit:

```bash
git status --short --branch
git show --stat --oneline HEAD
```

- [ ] Push `main`, create an annotated tag on that exact commit, and push the tag:

```bash
git push origin main
printf '%s\n' "$RELEASE_TITLE" | ./tools/verify-publication-policy.sh --stdin "tag metadata"
git tag -a "$TAG" -m "$RELEASE_TITLE"
git push origin "$TAG"
```

- [ ] Verify that the tag points to the same commit as local `main`:

```bash
test "$(git rev-parse "${TAG}^{commit}")" = "$(git rev-parse main)"
git ls-remote --exit-code origin "refs/tags/${TAG}"
```

## 6. Create and review a draft GitHub release

Use the approved `$RELEASE_NOTES` document bundled with the application. Do not rewrite or generate a second set of
release notes at this stage. It must already include an alpha warning, highlights, upgrade or data-layout cautions,
platform download guidance, JMBE status, and a request to verify downloads with `SHA256SUMS.txt`. Never include
credentials or private receiver details.

The preferred command uses the GitHub CLI. Install it and run `gh auth login` first if `gh` is unavailable.

- [ ] Create a draft prerelease and upload the eight prepared assets:

```bash
./gradlew verifyApprovedReleaseNotes
./tools/verify-publication-policy.sh --message-file "$RELEASE_NOTES"
gh release create "$TAG" \
  build/image/sdrtrunk-vce-*-v"${VERSION}".zip \
  build/image/SHA256SUMS.txt \
  --repo tylerwatt12/sdrtrunk-vce \
  --verify-tag \
  --target main \
  --title "$RELEASE_TITLE" \
  --notes-file "$RELEASE_NOTES" \
  --prerelease \
  --draft
```

If the GitHub CLI is unavailable, create a new draft at GitHub **Releases**, choose the existing `$TAG`, mark it as a
prerelease, paste the release notes, upload the same eight assets, and keep it as a draft.

- [ ] Review the draft release page. Confirm:

  - The release is marked **Pre-release**.
  - The tag and target commit are correct.
  - All seven platform ZIPs and `SHA256SUMS.txt` are present.
  - GitHub also shows its automatically generated source-code ZIP and tarball.
  - Platform guidance and warnings render correctly.
  - The GitHub text matches the in-application What's New document.
  - No secrets or private operational details appear in the notes or assets.

- [ ] Download at least one uploaded ZIP and `SHA256SUMS.txt` from the draft and compare the downloaded ZIP checksum
      with the local checksum entry.

## 7. Publish and verify

- [ ] Publish the reviewed draft as a prerelease:

```bash
gh release view "$TAG" --repo tylerwatt12/sdrtrunk-vce --json name,body --jq '.name, .body' | \
  ./tools/verify-publication-policy.sh --stdin "release metadata"
gh release edit "$TAG" \
  --repo tylerwatt12/sdrtrunk-vce \
  --draft=false \
  --prerelease
```

- [ ] Open the public release page in a signed-out/private browser window and verify that the notes and all downloads
      are visible.
- [ ] Confirm that GitHub reports the tag on the intended release commit.
- [ ] Download and checksum one public binary again.
- [ ] Record any receiver deployments separately; publishing a GitHub release does not deploy it to CUBI, BOSGAME, or
      the Mac receiver node.

## 8. If something goes wrong

Before publication, keep the release as a draft, delete the faulty draft and tag if necessary, fix the problem, and
restart the checklist. Commands for removing an unpublished draft and tag are intentionally explicit:

```bash
gh release delete "$TAG" --repo tylerwatt12/sdrtrunk-vce --yes
git push origin --delete "$TAG"
git tag -d "$TAG"
```

After publication, do not silently move the tag or replace binaries under the same version. Document the problem and
publish a new incremented alpha so users can distinguish the corrected files from downloads they may already have.
