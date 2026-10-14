# Privacy

Remember records audio only after the user starts capture and grants microphone access.

- Recordings and bookmark metadata are stored in the app's private local storage.
- The app has internet/network-state permission for optional SFTP backups and remote playback. It connects to the server you configure when you request a connection, upload, or remote recording, or enable scheduled backup. Automatic backup is off by default. There are no app accounts, analytics, advertising, or third-party upload services.
- Backups send audio, titles, timestamps, duration, bookmarks, file size, and checksums to your server. SSH encrypts the connection; these files remain readable on that server. Local deletion does not remove server copies.
- The watch generates its own SSH key. The private key is encrypted with an Android Keystore key; only the public key can be exported. Server configuration and transfer status remain in app-private preferences. The app fetches a server fingerprint without sending watch credentials, then asks for explicit first-time approval. Approved fingerprints are saved and checked before authentication; changed keys are blocked. Forgetting trust is an explicit action that pauses backups.
- Remote playback downloads audio into the app's private temporary cache. You can clear it under Backup → Advanced; Android may also evict cached files.
- Scheduled backups use Android JobScheduler with your selected charging/network constraints. Boot permission allows scheduled jobs to persist after restarting the watch. Jobs may retry failed transfers within your configured retry limit.
- Storage monitoring reads device capacity and Remember's own usage; it does not request access to another app's data.
- Recording uses a microphone foreground service so it can continue when the screen is off. An ongoing recording notification includes a Save action when notifications are allowed.
- Playback pauses when the playback activity leaves the foreground.
- Renaming updates a recording's local metadata. Deletion removes the recording after confirmation.
- Uninstalling the app removes local recordings, preferences, playback cache, and the stored SSH identity. It does not delete server backups. You will need to authorize a new watch public key after reinstalling. Restoring backups into the local library and phone transfer are not implemented.

Developer tools and test runs can access the debug app's files. Use disposable emulator recordings for testing, and do not publish private audio or logs in issues or pull requests.
