# Tool-box

### Basic ?.apk scan:
```
./baksmali list classes ?.apk | grep / | sort > ttbaksmali
(./dexdump ?.apk | grep "Class descriptor" | sed 's/ Class descriptor  : //' | grep / |sort | uniq > ttdexdump)
grep -Ff xSIGNs_fdroid.txt ttbaksmali | sort --ignore-case
```


### Code_signature additions to trackers.txt (Izzy lists https://gitlab.com/IzzyOnDroid/repo/tree/master/lib )
> Izzy existing ones: 3
```
°Tracks____com.automattic.android.tracks____https://github.com/Automattic/Automattic-Tracks-Android____nc____9999
°Google Play Install Referrer____com.android.installreferrer____https://developer.android.com/google/play/installreferrer____nc____8888
²?AWS Kinesis____com.amazonaws.auth.____https://github.com/aws-amplify/aws-sdk-android____nc____8888
```
> ETIP stand-by: 25 known + 2 possible
```
²Cloud Audit Logs____com.google.cloud.audit____https://cloud.google.com/logging/docs/audit____nc____8888
²CleanInsights____io.cleaninsights.sdk.piwik.____https://github.com/cleaninsights/cleaninsights-android-sdk/blob/master/cleaninsights-piwik-sdk/src/main/java/io/cleaninsights/sdk/CleanInsights.java____nc____9999
²CleanInsights____io.cleaninsights.sdk.CleanInsights.____https://github.com/cleaninsights/cleaninsights-android-sdk/blob/master/cleaninsights-piwik-sdk/src/main/java/io/cleaninsights/sdk/CleanInsights.java____nc____9999
²BugClipper____com.bugclipper.android.____https://bugclipper.com/sdk-doc/android/____nc____8888
²?Buglife____com.buglife.sdk____https://buglife.com/docs/android____nc____9999
²?Critic____io.inventiv.critic.____https://github.com/inventivtools/inventiv-critic-android____nc____9999
²?Game Sparks____com.gamesparks.sdk.____https://docs.gamesparks.com/sdk-center/android.html____nc____9999
²?PlayFab java____com.playfab.PlayFab____https://api.playfab.com/docs/getting-started/java-getting-started____nc____9999
²Woopra____com.woopra.tracking.____https://github.com/Woopra/woopra-android-sdk____nc____9999
²Askingpoint____com.askingpoint.____https://www.askingpoint.com/blog/document/add-sdk-to-project/____nc____8888
²Databox____com.databox.____https://developers.databox.com/sdks/  and  https://github.com/databox/databox-java____nc____9999
²Teliver____com.teliver.sdk____https://www.teliver.io/____nc____8888
²AppLink.io____io.applink.applinkio.AppLinkIO____https://support.applink.io/hc/en-us/articles/360021172012-Getting-Started-with-the-React-Native-SDK____nc____8888
²Pulsate____com.pulsatehq.____https://pulsate.readme.io/v2.0/docs/installing-sdk-android-studio____nc____8888
²SAS____com.sas.mkt.mobile.sdk.____https://communities.sas.com/t5/SAS-Communities-Library/Building-a-SAS-CI-enabled-mobile-app-for-Android/ta-p/354922____nc____8888
²Backendless____com.backendless.____https://backendless.com/docs/android/quick_start_guide.html____nc____8888
²froger_mcs____com.frogermcs.activityframemetrics____http://frogermcs.github.io/FrameMetrics-realtime-app-smoothness-tracking/____nc____8888
²FlowUp____io.flowup____https://github.com/flowupio/FlowUpAndroidSDK____nc____8888
²Parse____com.parse.Parse____https://docs.parseplatform.org/android/guide/#analytics____nc____8888
²AWS Kinesis____com.amazonaws.metrics.____https://github.com/aws-amplify/aws-sdk-android____nc____8888
²AWS Kinesis____com.amazonaws.util.AWSRequestMetrics.____https://github.com/aws-amplify/aws-sdk-android____nc____8888
²?DebugDrawer____io.palaima.debugdrawer.____https://github.com/palaima/DebugDrawer____nc____9999
²mTraction____com.mtraction.mtractioninapptracker.____http://docs.mtraction.com/index.php/docs/integrations/android-unity-integration/____nc____8888
²GravyAnalytics____com.timerazor.gravysdk.____http://gravyanalytics.com____api.findgravy.com|ws.findgravy.com____8888
²Skillz____com.skillz.Skillz____https://skillz.zendesk.com/hc/en-us/articles/208579286-Android-Core-Implementation____nc____8888

µ?Custom Activity On Crash____cat.ereza.customactivityoncrash____https://github.com/Ereza/CustomActivityOnCrash____nc____9999
µ?Acrarium____com.faendir.acra.____https://github.com/F43nd1r/Acrarium____nc____9999
```
> Missing ones: 0
```
```
> Exodus misc. : 2
```
Amazon Analytics (Amazon insights)____com.amazonaws.mobileconnectors.geo.tracker____https://github.com/aws-amplify/aws-sdk-android____mobileanalytics\.us-east-1\.amazonaws\.com____95
Amazon Mobile Analytics (Amplify)____com.amplifyframework.auth.cognito____https://github.com/aws-amplify/amplify-android____NC____423
```


> Out of scope (network only): 2
```
CrowdTangle 46
Xiti 10
```


#### Basic uses
```
export LC_ALL=C
cat trackers448NEW.txt | sort --ignore-case | awk -F "____" {'print $1"~"$5'} | uniq > xList.txt
cat trackers448NEW.txt | awk -F "____" {'print $2'} | sed -e "s/\./\//g"| sort --ignore-case  > xSIGNs.txt
```

#### Izzy X libs: https://gitlab.com/IzzyOnDroid/repo/tree/master/lib
```
cat xSIGNs_fdroid.txt | sed -r 's/\/$//' | xargs -I {} grep {} libinfo.txt
cat xSIGNs_fdroid.txt | sed -r 's/\/$//' | xargs -I {} grep {} libsmali.txt
(tr A-Z a-z < xSIGNs_fdroid.txt | sed -r 's/\/$//' | xargs -I {} grep {} libsmali.txt | wc -l)
```


###### Simplified stat (indexExodus eof:448)
455 IDs(xList) = 619 signatures(xSIGNs)