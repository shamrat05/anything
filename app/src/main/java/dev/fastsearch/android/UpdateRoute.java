package dev.fastsearch.android;

/** Edition, not the installer package reported by Android, selects the update channel. */
final class UpdateRoute {
    static final String PRODUCTION_ID = "com.callcraftstudio.anything";
    static final String GITHUB_RELEASE = "https://github.com/shamrat05/anything/releases/latest";
    static final String PLAY_LISTING = "https://play.google.com/store/apps/details?id=" + PRODUCTION_ID;
    static final String PLAY_PACKAGE = "com.android.vending";

    enum Edition {
        STANDARD("Standard · GitHub", "anything.apk"),
        SMS("SMS edition · GitHub", "anything-with-sms.apk"),
        PLAY("Google Play edition", null),
        PREVIEW("Preview build", null),
        UNKNOWN("Unrecognized edition", null);

        final String label;
        final String asset;

        Edition(String label, String asset) {
            this.label = label;
            this.asset = asset;
        }

        String download() {
            return asset == null ? null : GITHUB_RELEASE + "/download/" + asset;
        }
    }

    private UpdateRoute() {}

    static Edition installed(String applicationId, String flavor, boolean debug) {
        if (debug || !PRODUCTION_ID.equals(applicationId)) return Edition.PREVIEW;
        if ("github".equals(flavor)) return Edition.STANDARD;
        if ("full".equals(flavor)) return Edition.SMS;
        if ("play".equals(flavor)) return Edition.PLAY;
        return Edition.UNKNOWN;
    }
}
