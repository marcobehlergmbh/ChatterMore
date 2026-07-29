# ChatterMore

An unofficial, high-performance Mattermost client, that just works.

ChatterMore is a deliberately small, foreground-first Android client for Mattermost channels and direct messages. It targets Android 10 (API 29) and prioritizes immediate text rendering over feature breadth.

See the [ChatterMore Privacy Policy](PRIVACY_POLICY.md) for details about data
handled by the app.

The repository also contains a
[Google Play Data Safety worksheet](PLAY_DATA_SAFETY.md) for release
preparation.

## Features

- Connects to Mattermost using a server URL, username, and password.
- Remembers the host, resolved team, and username from the last successful connection without retaining them as credentials.
- Opens to a cached overview of joined channels and previous direct-message conversations.
- Highlights unread conversations with targeted real-time row updates.
- Loads channel, membership, and DM peer metadata in bulk without requesting every conversation's posts.
- Opens a direct-message conversation by tapping an author, avatar, or resolved `@username`.
- Sends and receives direct messages through the same bounded, real-time channel pipeline.
- Returns from every conversation to the overview with the header or Android back action.
- Loads recent history and pages backward with Mattermost's `before=<post_id>` cursor.
- Fetches missed/new messages with `after=<post_id>` and a defensive latest-page overlap.
- Receives `posted`, `post_edited`, and `post_deleted` WebSocket events while foregrounded.
- Inserts outgoing messages optimistically and reconciles REST/WebSocket acknowledgements with `pending_post_id`.
- Keeps optimistic messages visually normal for 500 ms, then underlines them until confirmed.
- Retries failed sends without losing the message text.
- Uploads gallery media, camera captures, and files with progress before sending them with optional text.
- Accepts Android single- and multi-file shares, preparing media previews or regular file attachments plus shared text without auto-sending.
- Validates known attachment sizes against the Mattermost server limit before upload.
- Renders authenticated image attachments from bounded server-generated previews.
- Renders cached user avatars with live Active, Away, or Offline presence badges.
- Renders lightweight YouTube thumbnail previews.
- Renders compact server-provided OpenGraph link previews without duplicating YouTube or Suno cards.
- Persists downloaded image bytes in a bounded app-private cache across launches.
- Adds inline play/stop controls for shared Suno song links.
- Streams authenticated video attachments in a full-screen player.
- Shows other attached filenames and downloads them through Android's download manager.
- Makes web links clickable with a regex-free, cached message parser.
- Renders Mattermost-style text smileys as Unicode emoji without changing stored messages.
- Highlights server-resolved `@username` mentions and visually distinguishes direct, `@channel`, `@all`, and `@here` attention.
- Refreshes the latest messages after a fling reaches the bottom or a 48dp bottom overscroll.
- Keeps a bounded 2,000-row in-memory feed and fetches evicted history again when needed.

Threads, reactions, Markdown rendering, full message offline storage, background sockets, and push notifications are intentionally out of scope.

## Build

Requirements:

- JDK 17 or newer supported by Gradle 9.1
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

Generate the local upload key once:

```bash
sh scripts/generate-upload-keystore.sh
```

This creates `.signing/chattermore-upload.jks` and
`.signing/keystore.properties`. Both are excluded from Git. Back up both files
in a secure password manager or encrypted backup; the upload key is intended
to remain stable for future Google Play releases.

Build and verify the signed release bundle:

```bash
./gradlew bundleRelease
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
```

Upload certificate SHA-256 fingerprint:

```text
52:6B:25:C0:BB:F8:CF:E5:48:F0:ED:47:68:8D:1A:89:A4:9D:23:D2:63:55:EF:A1:FE:A7:B7:48:66:40:E1:F4
```

## Connect

1. Use a Mattermost account that belongs to at least one team.
2. Install and open the app.
3. Enter the HTTPS server URL, username, and password.
4. Tap **Connect**.
5. Select a channel or direct-message conversation from the overview.

The password is encrypted with an AES-GCM key held by Android Keystore. It is exchanged for an in-memory Mattermost session token when the app connects. Android backup and cleartext network traffic are disabled. The server must present a certificate trusted by the Android system trust store.

When credentials are cleared or an automatic reconnect fails, the last successfully connected host, team, and username remain available on the setup screen. The password is not included in that non-secret remembered record.

## Architecture

- `MattermostApi`: the small REST surface for identity, bulk conversation metadata, read state, authors, history, and sends.
- `MattermostImageLoader`: bounded authenticated preview requests, background downsampling, cancellation, and memory caching.
- `MattermostSocket`: foreground WebSocket event flow with connection/sequence tracking.
- `ChannelRepository`: conversation directory, unread state, active-conversation switching, socket lifecycle, cursor requests, refreshes, and optimistic sends.
- `ConversationSummaryCache`: app-private, metadata-only stale-while-refresh overview snapshots.
- `PresenceStore`: batched status snapshots with real-time WebSocket updates and race protection.
- `FeedStore`: single canonical ordered feed, deduplication, pending reconciliation, and bounded memory.
- `MainActivity` / `ConversationAdapter` / `FeedAdapter`: XML Views, stable IDs, and targeted RecyclerView mutations.

REST and WebSocket races converge in `FeedStore` by server post ID and `pending_post_id`. Pending rows are never used as pagination cursors.

Conversation switches retain one WebSocket and one bounded 2,000-row `FeedStore`. Five recent server pages are held in a 30-second LRU cache. A generation gate prevents delayed work from a previous channel or DM from mutating the newly active conversation.

## Performance Validation

Run measurements on a physical low-end Android 10+ device using a release/R8 build. The implementation targets:

- Send tap to pending row: p95 at or below 50 ms.
- WebSocket callback to visible row: p95 at or below 100 ms, excluding network transit.
- Username tap to loading header: within one frame.
- Cached overview metadata visible as soon as authentication and team resolution complete.
- Constant-size directory bootstrap with no per-channel post or unread requests.
- One indexed adapter update per inactive-conversation socket post.
- Direct-channel response to first visible history: p95 at or below 100 ms, excluding network transit.
- Fewer than 1% of frames over 16.7 ms while scrolling 1,000 mixed-length rows.
- No loss or duplicates during a 20-post/second burst.

Unit tests cover ordering, cursor selection, pending reconciliation, deletion, thread filtering, bounded memory, and protocol parsing. Physical-device frame, memory, CPU, and network measurements remain device/server-specific and cannot be established by JVM tests.

The `benchmark` module supplies a deterministic 2,000-message benchmark build without credentials or network access. On a connected physical device, run:

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

It measures cold startup plus repeated full-feed scrolling with startup, frame-timing, and last-sample memory metrics.
