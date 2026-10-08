# Harbor keeps its WebView and bridge classes
-keepclassmembers class app.harbor.family.web.DeviceBridge {
    @android.webkit.JavascriptInterface <methods>;
}
