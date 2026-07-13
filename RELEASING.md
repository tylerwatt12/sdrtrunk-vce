# Publishing an Alpha Release

This checklist publishes a numbered `sdrtrunk-vce` alpha from `main` with self-contained Java 25 binaries for Windows,
Linux, and macOS. It follows the asset layout used for `v0.6.2-alpha-1`.

The examples below use `0.6.2-alpha-2`. Replace it with the version being published.

## 1. Prepare the release

- [ ] Confirm that the intended changes are merged into `main`.
- [ ] Confirm that receiver-node testing is complete for the changes being released.
- [ ] Confirm that the worktree is clean and local `main` is current:

```bash
git switch main
git status --short --branch
git pull --ff-only origin main
```

- [ ] Set shell variables for the release:

```bash
export VERSION=0.6.2-alpha-2
export TAG="v${VERSION}"
export RELEASE_TITLE="sdrtrunk-vce 0.6.2 Alpha 2"
```

- [ ] Set `projectVersion` in `gradle.properties` to `$VERSION`.
- [ ] Add a dated `$VERSION` section to the changelog in `README.md`.
- [ ] Check that no credentials, receiver addresses, private operational notes, databases, recordings, or local-only files
      are included in the pending changes.
- [ ] Review and commit the release preparation:

```bash
git diff --check
git diff
git add gradle.properties README.md
git commit -m "Prepare sdrtrunk-vce ${VERSION}"
```

## 2. Test the release commit

Java 25 is required. Build from the exact commit that will be tagged.

- [ ] Confirm Java and the release version:

```bash
java -version
./gradlew properties | sed -n '/^version:/p'
```

- [ ] Run the clean build and release-package safety check:

```bash
./gradlew clean build verifyNoBundledVoiceoptional processing
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
git tag -a "$TAG" -m "$RELEASE_TITLE"
git push origin "$TAG"
```

- [ ] Verify that the tag points to the same commit as local `main`:

```bash
test "$(git rev-parse "${TAG}^{commit}")" = "$(git rev-parse main)"
git ls-remote --exit-code origin "refs/tags/${TAG}"
```

## 6. Create and review a draft GitHub release

Prepare release notes in a temporary file outside the repository, for example `/tmp/sdrtrunk-vce-release-notes.md`.
Include an alpha warning, highlights, upgrade or data-layout cautions, platform download guidance, JMBE status, and a
request to verify downloads with `SHA256SUMS.txt`. Never include credentials or private receiver details.

The preferred command uses the GitHub CLI. Install it and run `gh auth login` first if `gh` is unavailable.

- [ ] Create a draft prerelease and upload the eight prepared assets:

```bash
gh release create "$TAG" \
  build/image/sdrtrunk-vce-*-v"${VERSION}".zip \
  build/image/SHA256SUMS.txt \
  --repo tylerwatt12/sdrtrunk-vce \
  --verify-tag \
  --target main \
  --title "$RELEASE_TITLE" \
  --notes-file /tmp/sdrtrunk-vce-release-notes.md \
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
  - No secrets or private operational details appear in the notes or assets.

- [ ] Download at least one uploaded ZIP and `SHA256SUMS.txt` from the draft and compare the downloaded ZIP checksum
      with the local checksum entry.

## 7. Publish and verify

- [ ] Publish the reviewed draft as a prerelease:

```bash
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
