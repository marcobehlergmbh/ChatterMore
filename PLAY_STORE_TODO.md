# Google Play publishing TODO

Work through this list from top to bottom. The first submission can proceed
without implementing every possible user-generated-content policy feature.

Last updated: July 29, 2026

## Current status

Completed locally:

- ChatterMore branding and the permanent `com.marcobehler.chattermore` package ID.
- Adaptive, round, and legacy launcher icons.
- Local release signing and a signed, verified Android App Bundle.
- Privacy policy draft, Data Safety worksheet, and store-listing copy.
- Unit tests, Android lint, debug build, release build, and benchmark build.

Still requires manual or external work:

- Back up the upload keystore and its credentials somewhere secure.
- Publish the repository, then use the rendered GitHub privacy-policy URL in
  Google Play and add it as a visible link in the app.
- Create the store icon, feature graphic, and screenshots.
- Set up a reviewer-accessible Mattermost server and test account.
- Complete the Play Console forms, declarations, testing requirements, and
  submission.
- Run the final release on real supported Android devices; no device was
  attached during the local verification.

Current build facts:

- `targetSdk`: 36
- `versionCode`: 1
- Release bundle: `app/build/outputs/bundle/release/app-release.aab`
- Bundle signature: verified locally
- Upload certificate SHA-256 fingerprint: documented in `README.md`

Next actions:

1. Back up `.signing/chattermore-upload.jks` and
   `.signing/keystore.properties`.
2. Publish the repository to GitHub and replace the placeholder privacy-policy
   URL with its final rendered GitHub URL.
3. Create the Play Store graphics and screenshots.
4. Create the Play Console app and reviewer environment.

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
- [ ] Back up the keystore and credentials somewhere secure outside Git.
- [x] Configure the Gradle `release` build to use the upload key without
      committing secrets.
- [ ] Enable Play App Signing.
- [x] Produce a signed release `.aab` and verify its signature.

The local release bundle is signed with the ChatterMore upload key. Back up
`.signing/chattermore-upload.jks` and `.signing/keystore.properties` before
depending on this key for Google Play releases.

## Privacy

- [ ] Publish a public privacy-policy webpage.
- [ ] Add a visible in-app link to the privacy policy.
- [x] State that the app sends credentials, account/profile information,
      messages and attachments to the Mattermost server selected by the user.
- [x] Mention local credential and message caching, notifications, downloaded
      files, and requests to external preview hosts such as YouTube or Suno.
- [x] State that the app contains no advertising or analytics SDKs.
- [x] Provide the privacy/support contact address `info@marcobehler.com`.
- [x] Prepare the Play Data Safety answers in `PLAY_DATA_SAFETY.md`.
- [ ] Enter and submit those answers in Play Console.

The canonical policy is `PRIVACY_POLICY.md`. After publishing this repository
on GitHub, use the public rendered Markdown URL in Play Console and the app.

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
- [ ] Create a 512 x 512 PNG store icon.
- [ ] Create a 1024 x 500 PNG or JPEG feature graphic.
- [ ] Capture at least two accurate phone screenshots.
- [ ] Choose the app category, countries/regions and Free/Paid status.

Starting as **Free** is simplest. An app that has been offered for free cannot
later be changed to paid under the same package name.

## Play Console declarations

- [ ] Create and verify the Play developer account.
- [ ] Verify developer contact details and identity.
- [ ] Register/check the package name in Play Console.
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
- [ ] Confirm `targetSdk` remains at API 36 or newer.
- [ ] Confirm `versionCode` is unique for the upload.
- [ ] Save the release R8 `mapping.txt` file.
- [ ] Upload the signed `.aab` to the appropriate test track.
- [ ] Review the pre-launch report and fix crashes or serious compatibility
      failures.
- [ ] Submit the production release for review.

## Defer unless Google rejects the app

- [ ] Add dedicated in-app user/content reporting.
- [ ] Add user blocking or broader moderation controls.
- [ ] Address non-blocking lint warnings and store-listing optimization.
