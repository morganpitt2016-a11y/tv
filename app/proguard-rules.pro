# Keep names readable in crash reports (personal build; size comes from shrinking, not renaming).
-dontobfuscate

# The Anthropic SDK ships its own rules (META-INF/proguard) for Jackson/Kotlin reflection.
# Optional or desktop-Java classes its libraries mention but never use on Android:
-dontwarn java.beans.**
-dontwarn java.lang.invoke.**
-dontwarn java.net.http.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn javax.naming.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn io.opentelemetry.**
-dontwarn io.swagger.**
-dontwarn com.github.victools.**
-dontwarn com.fasterxml.classmate.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.standardwebhooks.**
-dontwarn kotlinx.coroutines.**
-dontwarn jakarta.**
-dontwarn org.jetbrains.annotations.**
-dontwarn edu.umd.cs.findbugs.annotations.**
