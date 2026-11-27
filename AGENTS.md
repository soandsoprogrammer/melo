# Working in this repository

- Work on the current branch. Do not create branches or worktrees unless asked.
- Melo has no Spotify native runtime. Spotify remains a metadata source. Spotify tracks may play through a confident match on the connected music server.
- The client uses the native Android protocols. Do not substitute Spotify for Developers OAuth, the Web API, App Remote, a playback WebView or a dependency on the installed Spotify app.
- The Android client constants in network messages are those of the analysed package. They are not a developer app registration.
- A moving position, the PLAYING state or a full buffer does not prove there is sound. New tests must check the decoded signal after seeks and across track transitions.
- Keep three things apart: contracts checked statically, tests with doubles, and successes against the real services. Playlist writes use Playlist4 over HTTP (`playlist/v2/.../changes`); call them working only after a run against a real account (`SpotifyPlaylistWriteTest`).
- Shuffle reorders the queue inside `SpotifyPlayer`. Media3's own shuffle mode stays off.
- Play server tracks through their authenticated OpenSubsonic original streams. Keep credentials out of media IDs, queues, links and logs. Device files and explicit add-on rows use their own media sources.
- Everything in the repository is written in English. The app ships English (default), French and Simplified Chinese strings.
