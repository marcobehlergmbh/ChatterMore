# Google Play Data Safety worksheet

Last reviewed: July 29, 2026

Use this worksheet when completing **Policy and programs > App content > Data
safety** for ChatterMore in Google Play Console.

This is a conservative mapping of the current app. Recheck it whenever the app
adds an SDK, permission, backend, analytics, advertising, account creation, or
new data-handling feature.

Official reference:
[Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)

## Important classification assumption

ChatterMore has no developer-operated backend. It sends data directly to the
Mattermost server explicitly chosen by the user.

This worksheet classifies those transfers as:

- **Collected:** Yes. Google defines collection as transmitting user data off
  the device, including transmission to a third-party server.
- **Shared:** No. The Mattermost server is the destination the user explicitly
  selects for authentication, messaging, and file transfer. This worksheet
  relies on Google's exception for transfers resulting from a specific
  user-initiated action where the user reasonably expects the transfer.

If Google determines that a selected Mattermost server does not qualify for
that sharing exception, change the affected types from **Collected** to
**Collected and shared**. Do not change the privacy policy's description of
the transfer.

## Data collection and security

Enter these answers:

| Play Console question | Answer | Reason |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** | Authentication, messages, and optional files are transmitted to the selected Mattermost server. |
| Is all user data collected by your app encrypted in transit? | **Yes** | ChatterMore requires HTTPS for Mattermost and preview/media requests. |
| Do you provide a way for users to request that their data is deleted? | **No** | ChatterMore has no backend to delete. Local data is removed through Android app storage/uninstall; server data is controlled by the selected Mattermost server. |
| Does the app allow users to create an account? | **No** | It only signs in to an existing account on a separately operated Mattermost server. |

The deletion answer is intentionally conservative. Do not claim that clearing
the saved connection deletes the Mattermost account or server-side data.

## Data types to select

Select only the following types.

### Personal info

#### User IDs

Examples in ChatterMore: Mattermost username, account ID, and session identity.

| Question | Answer |
| --- | --- |
| Collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **No** |
| Is collection required or optional? | **Required** |
| Purpose | **App functionality** |

The app cannot authenticate or provide its primary functionality without a
Mattermost user identity.

#### Email address

Mattermost can accept an email address as a login ID even though ChatterMore's
field is labelled "Username." Select this type conservatively.

| Question | Answer |
| --- | --- |
| Collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **No** |
| Is collection required or optional? | **Optional** |
| Purpose | **App functionality** |

A user can authenticate with a username instead of an email address.

### Messages

#### Other in-app messages

Examples in ChatterMore: channel messages, direct messages, and edited message
content sent by the user.

| Question | Answer |
| --- | --- |
| Collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **No** |
| Is collection required or optional? | **Optional** |
| Purpose | **App functionality** |

Sending or editing a message is a user-initiated action. A user can use the app
without sending a new message.

### Photos and videos

Complete **Photos** and **Videos** separately with the same answers:

| Question | Answer |
| --- | --- |
| Collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **No** |
| Is collection required or optional? | **Optional** |
| Purpose | **App functionality** |

These are transmitted only when the user selects, captures, shares, or uploads
them.

### Audio files

Select all three audio subtypes because the general file picker can upload
arbitrary user-selected audio:

- **Voice or sound recordings**
- **Music files**
- **Other audio files**

Use the same answers for each:

| Question | Answer |
| --- | --- |
| Collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **No** |
| Is collection required or optional? | **Optional** |
| Purpose | **App functionality** |

ChatterMore does not record microphone audio. These categories cover audio
files the user deliberately selects or shares with the app.

### Files and docs

#### Files and docs

| Question | Answer |
| --- | --- |
| Collected, shared, or both? | **Collected** |
| Is this data processed ephemerally? | **No** |
| Is collection required or optional? | **Optional** |
| Purpose | **App functionality** |

Files and filenames are transmitted only when the user selects, shares, or
uploads them.

## Data types not to select

Do not select these for the current app:

- Location
- Name
- Phone number or physical address
- Race, ethnicity, beliefs, sexual orientation, or other personal information
- Financial information
- Health or fitness information
- Emails as message content
- SMS or MMS
- Calendar
- Contacts
- App interactions, search history, installed apps, or other actions
- Web browsing history
- Crash logs, diagnostics, or other performance data
- Device or other IDs

### IP addresses and external previews

Ordinary HTTPS connections reveal an IP address to the destination server.
Google says IP addresses should be classified according to how they are
actually used. ChatterMore does not use IP addresses to infer location, create
an identifier, perform analytics, or build a profile, so this worksheet does
not select **Approximate location** or **Device or other IDs** solely because
network hosts can observe an IP address.

ChatterMore can request YouTube thumbnails, Open Graph images, and Suno media.
The privacy policy discloses those requests. This worksheet does not select
**Web browsing history** because the app does not collect or transmit a user's
browsing-history dataset or operate an in-app browser.

## Security practices

The following statements are supported by the current implementation:

- Mattermost connections must use HTTPS.
- Cleartext traffic is disabled in the manifest and network security config.
- Saved passwords and background session tokens use AES-GCM with keys held by
  Android Keystore.
- The recent conversation snapshot is encrypted locally.
- Android backup and device-transfer backup are disabled.
- Broad storage, contacts, location, microphone, and camera permissions are not
  requested.
- There are no advertising, analytics, Firebase, or crash-reporting SDKs.

Do not claim an independent security review unless one is completed through a
Google-recognized program.

## Final Play Console check

Before submitting the form:

- [ ] Confirm the published app has the same dependencies and permissions
      reviewed here.
- [ ] Confirm `PRIVACY_POLICY.md` is publicly accessible through the final
      GitHub URL.
- [ ] Add that public URL to the Play Console privacy-policy field.
- [ ] Add the same public URL inside the app.
- [ ] Compare the Play store preview against this worksheet.
- [ ] Save a Play Console CSV export with the release records.

