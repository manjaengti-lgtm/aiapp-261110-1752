# Full Status Share

Share a whole long video to WhatsApp Status or Facebook in one tap, in original quality.

## How it works
- WhatsApp Status allows at most 60 s per clip. The app cuts your video into parts of 59 s or less, cutting
  at keyframes WITHOUT re-encoding (bit-for-bit original quality), then sends all parts to WhatsApp in a single
  share. They post as back-to-back statuses that play like one video.
- WhatsApp takes up to 30 videos per share (about 29 minutes). Longer videos are shared in batches.
- Facebook posts have no 60 s limit, so "Share full video to Facebook" sends the original file untouched.
- You can also share from your Gallery: Share → Full Status Share.

## Build
Push this folder to a GitHub repo. The "Build APK" workflow builds it and attaches the debug and release APKs
as an artifact (Actions tab → latest run → FullStatusShare-apk).
