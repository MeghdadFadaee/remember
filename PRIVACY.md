# Privacy

Remember records audio only after the user starts capture and grants microphone access.

- Recordings and bookmark metadata are stored in the app's private local storage.
- The app has no internet permission, accounts, analytics, advertising, or upload feature.
- Storage monitoring reads device capacity and Remember's own usage; it does not request access to another app's data.
- Recording uses a microphone foreground service so it can continue when the screen is off. An ongoing recording notification includes a Save action when notifications are allowed.
- Playback pauses when the playback activity leaves the foreground.
- Renaming updates a recording's local metadata. Deletion removes the recording after confirmation.
- Uninstalling the app removes its recordings and preferences. Export and phone transfer are not implemented.

Developer tools and test runs can access the debug app's files. Use disposable emulator recordings for testing, and do not publish private audio or logs in issues or pull requests.
