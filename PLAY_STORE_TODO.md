# Google Play publishing TODO

Work through this list from top to bottom. The first submission can proceed
without implementing every possible user-generated-content policy feature.

Last updated: October 26, 2026

## Current Play Console checkpoint

Verified on the public Google Play listing on October 26, 2026:

- Production: `0.1.4` (`versionCode` 5) is **published** and publicly available
  at `https://play.google.com/store/apps/details?id=com.marcobehler.chattermore`.
  The listing shows "Released on" August 16, 2026 and "Updated on"
  September 11, 2026. It replaced the previous production version `0.1.2` (3).

Verified through the Google Play Developer API on September 11, 2026:

- Production: `0.1.4` (`versionCode` 5) was created for full rollout to 177 of
  177 countries and entered review in Play Console on September 11, 2026.
- Internal testing: `0.1.4` (`versionCode` 5), uploaded and rolled out with
  status `completed`. Adds a searchable channel/DM picker before external
  file uploads, with confirmation before discarding an existing draft.
- The uploaded bundle's SHA-256 matches the signed local archive in
  `.release-artifacts/0.1.4-5/`, which also contains the R8 mapping and verified
  Play track status. Release validation passed all 171 unit tests and lint.
  The new sharing flow has not been verified on a device.
- Production promotion records and release notes are archived alongside the
  bundle in `.release-artifacts/0.1.4-5/`.
- Previous internal release `0.1.3` (4) included keyboard dismissal, composer
  focus, and conversation history-loading improvements; the keyboard fixes
  passed four emulator regression tests.

The initial-submission checklist below is historical and has not been
reconciled with the completed production release. Its unchecked items do not
establish the current state of Play Console.

## Historical initial-submission checkpoint (August 1, 2026)

The Play developer account has been verified, the `ChatterMore` Play Console
app has been created, and version `0.1.0` (`versionCode` 1) has been rolled out
to internal testing. CLI publishing is configured, and version `0.1.1`
(`versionCode` 2) was successfully built, uploaded, and rolled out to internal
testing through the Google Play Developer API on August 1, 2026.

Resume with:

1. Add the internal tester accounts and open the tester opt-in link.
2. Install the Play-distributed build on the Pixel 7a and complete the signed
   release smoke test.
3. Enter the public privacy-policy URL:
   `https://github.com/marcobehlergmbh/ChatterMore/blob/main/PRIVACY_POLICY.md`
4. Continue with the reviewer environment, store listing, declarations, Data
   Safety form, and remaining testing requirements described below.

The privacy-policy URL and sanitized screenshot directory were verified as
publicly accessible on July 29, 2026. The upload keystore and its credentials
have been backed up securely outside Git.

## Current status

Completed locally:

- ChatterMore branding and the permanent `com.marcobehler.chattermore` package ID.
- Adaptive, round, and legacy launcher icons.
- Local release signing and a signed, verified Android App Bundle.
- Privacy policy draft, Data Safety worksheet, and store-listing copy.
- Play Store icon, feature graphic, and five sanitized phone screenshots.
- Unit tests, Android lint, debug build, release build, and benchmark build.
- Guarded CLI publishing to the Play internal track with versioned local AAB
  and R8 mapping archives.

Still requires manual or external work:

- Enter the published GitHub privacy-policy URL in Google Play.
- Set up a reviewer-accessible Mattermost server and test account.
- Complete the Play Console forms, declarations, testing requirements, and
  submission.
- Run the final release on real supported Android devices. The sanitized
  benchmark fixtures were captured on a Pixel 7a running API 37, but the signed
  release still requires its full device test pass.

Current build facts:

- `targetSdk`: 36
- `versionCode`: 2
- Release bundle: `app/build/outputs/bundle/release/app-release.aab`
- Bundle signature: verified locally
- Upload certificate SHA-256 fingerprint: documented in `README.md`

Next actions:

1. Add internal testers, opt in, and install the Play-distributed build.
2. Run the internal release through the final device test matrix.
3. Create the reviewer environment and enter the published privacy-policy URL.
4. Complete the store listing, declarations, and Data Safety form.

## App identity

- [x] Choose the original app name `ChatterMore`.
- [x] Change `app_name` to `ChatterMore`.
- [x] Set the permanent application ID and namespace to
      `com.marcobehler.chattermore`.
- [x] Describe the app as unofficial in the store listing without implying
      endorsement by Mattermost.
- [x] Create an original adaptive launcher icon and round icon.

Do these before creating the Play Console app. The application ID becomes the
permanent Play package name.

## Release signing

- [x] Generate a permanent upload keystore.
- [x] Back up the keystore and credentials somewhere secure outside Git.
- [x] Configure the Gradle `release` build to use the upload key without
      committing secrets.
- [x] Enable Play App Signing.
- [x] Produce a signed release `.aab` and verify its signature.

The local release bundle is signed with the ChatterMore upload key. Back up
`.signing/chattermore-upload.jks` and `.signing/keystore.properties` before
depending on this key for Google Play releases.

## Privacy

- [x] Publish a public privacy-policy webpage.
- [x] Add a visible in-app link to the privacy policy.
- [x] State that the app sends credentials, account/profile information,
      messages and attachments to the Mattermost server selected by the user.
- [x] Mention local credential and message caching, notifications, downloaded
      files, and requests to external preview hosts such as YouTube or Suno.
- [x] State that the app contains no advertising or analytics SDKs.
- [x] Provide the privacy/support contact address `info@marcobehler.com`.
- [x] Prepare the Play Data Safety answers in `PLAY_DATA_SAFETY.md`.
- [ ] Enter and submit those answers in Play Console.

The canonical policy is `PRIVACY_POLICY.md`. Its public URL is
`https://github.com/marcobehlergmbh/ChatterMore/blob/main/PRIVACY_POLICY.md`.
Use that URL in Play Console and the app.

Likely Data Safety categories include user IDs, messages, photos, videos,
audio, and files/documents. These are used for app functionality and are sent
over HTTPS. The app does not create Mattermost accounts, so a separate account
deletion feature should not be required.

## Google review access

- [ ] Set up an Internet-accessible Mattermost demo server.
- [ ] Create a dedicated reviewer account with representative channels,
      messages and attachments.
- [ ] Ensure the credentials do not expire and do not require MFA or a
      one-time code.
- [ ] Add the server URL, username, password and short English instructions to
      Play Console's **App access** section.

## Store listing

- [x] Finish the store title, short description and full description drafts in
      `PLAY_STORE_LISTING.md`.
- [x] Clearly identify the app as unofficial.
- [x] Provide the support email address `info@marcobehler.com`.
- [x] Create a 512 x 512 PNG store icon.
- [x] Create a 1024 x 500 PNG or JPEG feature graphic.
- [x] Capture at least two accurate phone screenshots.
- [ ] Choose the app category, countries/regions and Free/Paid status.

Starting as **Free** is simplest. An app that has been offered for free cannot
later be changed to paid under the same package name.

## Play Console declarations

- [x] Create and verify the Play developer account.
- [x] Verify developer contact details and identity.
- [x] Register/check the package name in Play Console.
- [ ] Declare that the app contains no ads.
- [ ] Complete the target-audience questionnaire.
- [ ] Complete the content-rating questionnaire.
- [ ] Complete App Access and Data Safety.
- [ ] Answer that the app does not offer account creation.

If using a personal developer account created after November 13, 2023:

- [ ] Run a closed test with at least 12 opted-in testers for 14 continuous
      days.
- [ ] Apply for production access after the test.

## Final verification and submission

- [ ] Test the signed release on an Android 10 device.
- [ ] Test the signed release on a current Android device.
- [ ] Verify login, reconnect and clearing the saved connection.
- [ ] Verify channels, direct messages, sending and editing messages.
- [ ] Verify uploads, downloads, camera/gallery/file selection and Android
      sharing.
- [ ] Verify notification permission and background message checks.
- [x] Confirm `targetSdk` remains at API 36 or newer.
- [x] Confirm `versionCode` is unique for the latest upload.
- [x] Save the release R8 `mapping.txt` file for version `0.1.1` (2).
- [x] Upload the signed `.aab` to the internal testing track.
- [ ] Review the pre-launch report and fix crashes or serious compatibility
      failures.
- [ ] Submit the production release for review.

## Defer unless Google rejects the app

- [ ] Add dedicated in-app user/content reporting.
- [ ] Add user blocking or broader moderation controls.
- [ ] Address non-blocking lint warnings and store-listing optimization.
