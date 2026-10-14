# SFTP backup setup

Remember needs an existing SSH server with SFTP enabled. No database or custom application server is required. Use a dedicated backup account and folder. The implementation streams one file at a time, but actual memory and CPU consumption must be measured on your server before relying on its 128 MB budget.

## Server preparation

1. Create a dedicated account using your operating system's normal account tools. Give it read/write access to a folder reserved for Remember. Avoid using an administrator or root account.
2. Ensure that the SSH server supports SFTP and Ed25519 public-key authentication. New watch identities use Ed25519. Existing RSA watch keys are preserved; use **Replace with Ed25519** on the Server page to migrate, then authorize the new public key and re-enable backups. This operation pauses backups. RSA, ECDSA, and Ed25519 server host keys are supported; ECDSA/RSA remain preferred when a server offers multiple host-key types, preserving existing fingerprint pins.
3. The watch can fetch the fingerprint automatically with **Connect & trust**; no fingerprint typing is required. First-time approval trusts the server reached on that connection. For stronger assurance, open **View fingerprint** on the approval screen and compare it with one obtained through a trusted administrative connection before approving. For example, on an OpenSSH server offering an ECDSA host key:

   ```sh
   ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub -E sha256
   ```

   Compare the `SHA256:...` value with the watch's read-only display if desired. Subsequent fingerprint mismatches block authentication and transfers. For a legitimate server-key change, verify it first, then use **Menu → Advanced → Server identity → Forget trust** and approve the new identity. Forgetting trust pauses backups and turns automatic backup off.
4. In Remember, open **Menu → Server**, then **Create watch key**. Export or copy the public key and add its single line to the dedicated account's `~/.ssh/authorized_keys`. Keep normal OpenSSH ownership and permissions: the account owns `.ssh` and its files, `.ssh` is mode 700, and `authorized_keys` is mode 600.
5. Restrict the account to SFTP and its intended storage area using your server's supported configuration. For OpenSSH, a dedicated-user `Match` block can disable shell use and forwarding:

   ```text
   Match User remember-backup
       ForceCommand internal-sftp
       AllowTcpForwarding no
       X11Forwarding no
       PermitTTY no
   ```

   This block restricts session capabilities; it does not itself confine filesystem access. Use filesystem permissions or a properly configured chroot for confinement. A chroot has additional root-ownership requirements and changes the absolute folder path visible to the app. Validate SSH configuration before reloading it and keep an administrative session available.

## Watch setup

Enter the address without `ssh://`, SSH port, account name, and an **existing absolute folder path** as seen by that SFTP account. Remember does not create the root backup folder. Choose **Connect & trust**, review the server identity, and tap **Trust server**. The app saves the fingerprint automatically. Cancel leaves the server untrusted; discovery sends no watch credentials. If a previously trusted server presents a different key, it is blocked. **Save server** can store an untrusted draft, but uploads remain disabled until approval. The watch public key must be authorized before **Test connection** can succeed.

Choose **Back up now** to queue saved recordings. Automatic backup remains off until enabled on its dedicated page. Retry attempts and socket timeouts are adjustable under Advanced. Pausing all backups also disables automatic backup.

Password login and private-key import are not currently supported. The private watch key is encrypted locally using Android Keystore and cannot be exported through the UI. After uninstalling or changing watches, authorize a newly generated public key on the server.

## Files and integrity

Each recording uses its stable ID for `<id>.m4a` and `<id>.json`. The JSON manifest includes a schema version, title, creation time, duration, bookmark timestamps, audio byte count, and SHA-256. Audio is uploaded to `<id>.m4a.upload`, resumed after interruptions, and read back for verification. Metadata is published last as the completion marker. Failed verification removes the app's incomplete audio upload so a retry can start cleanly. No remote shell or checksum command is used.

Verification requires downloading the uploaded audio once, so account for both directions when budgeting bandwidth. SSH compression is disabled for the already-compressed audio. Backups never erase local recordings. Local deletion leaves server copies intact. Successful local receipts do not detect a later manual deletion on the server; remote browsing reflects the current folder contents.

Server recordings downloads a selected file into a single temporary playback cache and verifies its checksum before playback. At least the full recording size plus 16 MB free space is required. The browser loads up to 1,000 manifests per folder. Files created by other applications or unsupported/corrupt manifests are ignored.

SSH protects transfers but does not encrypt the audio and metadata stored on the server. Restore into the local library, client-side backup encryption, remote deletion, bandwidth limits, and exact-clock-time scheduling are future work.

## Testing

Never point integration tests at personal backup storage. The network tests are opt-in and require a disposable loopback SFTP fixture offering the account `backup-test`, folder `/remember`, and a known RSA/ECDSA/Ed25519 host fingerprint. A loopback-only fixture is included in `tools/sftp_fixture.py`. It creates a fresh temporary folder, accepts only the test account with an Ed25519 identity, and deletes its server files when stopped. Prepare it in a temporary Python environment (Python 3.9+):

```sh
python3 -m venv /tmp/remember-sftp-env
/tmp/remember-sftp-env/bin/pip install paramiko==5.0.0
/tmp/remember-sftp-env/bin/python tools/sftp_fixture.py --state-file /tmp/remember-sftp-state.json
```

The state JSON contains the fixture fingerprint and authentication-attempt count. Discovery-only testing should leave the count at zero. With the fixture listening on host port 2222, connect only the intended disposable emulator:

```sh
adb -s TEST_SERIAL reverse tcp:2222 tcp:2222
adb -s TEST_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s TEST_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s TEST_SERIAL shell am instrument -w \
  -e class meghdad.remember.BackupTest \
  -e sftpPort 2222 \
  -e sftpFingerprint SHA256:FIXTURE_FINGERPRINT \
  meghdad.remember.test/androidx.test.runner.AndroidJUnitRunner
```

The `connectAndTrustRequiresApprovalAndBlocksChangedIdentity` test exercises Cancel, explicit approval, and changed-key rejection. Add `-e saveTrustScreenshot true` to save a test approval-screen screenshot in the target app's cache. Without the fingerprint argument, network tests are skipped; UI, metadata, and key-storage tests still run. The manual-job test requires the disposable emulator to have a network connection. These tests create server files and local fixtures, change server settings, and cancel backup jobs. The server fixture should be discarded after the run.
