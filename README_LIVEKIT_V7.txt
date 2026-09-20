TwoPersonMessenger V7.0 - LiveKit call engine
- Message system unchanged.
- Removed direct io.github.webrtc-sdk dependency.
- Calls now use io.livekit:livekit-android:2.28.2.
- Old PHP call tables remain only for ringing / accept / reject / hangup control.
- Audio/video media and SDP/ICE are handled by LiveKit Cloud.
- API secret is NOT inside the APK.
- First stable build intentionally renders remote video only; local preview is hidden.
