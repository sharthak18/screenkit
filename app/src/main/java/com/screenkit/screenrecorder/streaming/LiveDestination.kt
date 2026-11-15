package com.screenkit.screenrecorder.streaming

/** Platform hand-off is not OAuth/account linking. Never present it as an authenticated connection. */
internal enum class LiveDestination(val label: String, val defaultServer: String, val dashboardUrl: String?, val guidance: String) {
    YOUTUBE("YouTube", "rtmps://a.rtmps.youtube.com/live2", "https://www.youtube.com/livestreaming/redirect",
        "Open YouTube's live tools and sign in there. If your channel has live streaming enabled, choose streaming software and copy its server URL and stream key. YouTube may ask you to confirm Go Live in its dashboard."),
    FACEBOOK("Facebook", "rtmps://live-api-s.facebook.com:443/rtmp", "https://www.facebook.com/live/producer",
        "Open Facebook Live Producer and sign in there. Choose streaming software, copy the server URL and stream key, and return here. Check your audience and finish publishing in Facebook if prompted."),
    TIKTOK("TikTok", "", "https://www.tiktok.com/live",
        "Open TikTok and check your account's LIVE options. ScreenKit needs streaming-software access with a server URL and stream key; not every LIVE account receives these. If they are unavailable, use TikTok's own supported LIVE flow instead."),
    CUSTOM("Custom server", "", null,
        "Use the RTMP/RTMPS server URL and stream key provided by your streaming service. Prefer RTMPS. No account connection is performed by ScreenKit.");
}
