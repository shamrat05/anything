package dev.fastsearch.android;

final class UpdateRouteTest {
    private static int checks;

    public static void main(String[] args) {
        String packageId=UpdateRoute.PRODUCTION_ID;
        check(UpdateRoute.installed(packageId,"github",false)==UpdateRoute.Edition.STANDARD,"GitHub default");
        check(UpdateRoute.installed(packageId,"full",false)==UpdateRoute.Edition.SMS,"GitHub SMS");
        check(UpdateRoute.installed(packageId,"play",false)==UpdateRoute.Edition.PLAY,"Play");
        for(String flavor:new String[]{"github","full","play","future"}) {
            check(UpdateRoute.installed(packageId,flavor,true)==UpdateRoute.Edition.PREVIEW,"debug "+flavor);
            check(UpdateRoute.installed(packageId+".fresh",flavor,false)==UpdateRoute.Edition.PREVIEW,"preview "+flavor);
        }
        check(UpdateRoute.installed(packageId,"other",false)==UpdateRoute.Edition.UNKNOWN,"future flavor fails closed");
        check(UpdateRoute.installed(packageId,null,false)==UpdateRoute.Edition.UNKNOWN,"missing flavor fails closed");
        check("https://github.com/shamrat05/anything/releases/latest/download/anything.apk"
                .equals(UpdateRoute.Edition.STANDARD.download()),"standard download");
        check("https://github.com/shamrat05/anything/releases/latest/download/anything-with-sms.apk"
                .equals(UpdateRoute.Edition.SMS.download()),"SMS download");
        check(UpdateRoute.Edition.PLAY.download()==null,"Play has no APK link");
        check(UpdateRoute.Edition.PREVIEW.download()==null,"preview has no production APK link");
        check(UpdateRoute.Edition.UNKNOWN.download()==null,"unknown has no APK link");
        check("https://play.google.com/store/apps/details?id=com.callcraftstudio.anything"
                .equals(UpdateRoute.PLAY_LISTING),"production Play listing");
        System.out.println("PASS "+checks+" offline app update route assertions.");
    }

    private static void check(boolean pass,String label) {
        if(!pass)throw new AssertionError(label);
        checks++;
    }
}
