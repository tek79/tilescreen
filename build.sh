#!/bin/sh
# Builds the sideloadable APK with the AOSP tools Ubuntu packages (aapt, dx, apksigner, zipalign).
set -e
cd "$(dirname "$0")"
JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
OUT=build/apk
rm -rf $OUT && mkdir -p $OUT/gen $OUT/classes
aapt package -f -m -J $OUT/gen -M AndroidManifest.xml -S res -A assets -I $JAR -F $OUT/base.apk
javac -Xlint:-options --release 8 -encoding UTF-8 -classpath $JAR -d $OUT/classes \
    $(find src $OUT/gen -name '*.java')
dalvik-exchange --dex --min-sdk-version=17 --output=$OUT/classes.dex $OUT/classes
(cd $OUT && aapt add -f base.apk classes.dex >/dev/null)
zipalign -f -p 4 $OUT/base.apk $OUT/aligned.apk
KS=${KEYSTORE:-tilescreen.jks}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass tilescreen -keypass tilescreen -alias tilescreen \
    -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Tile Screen" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:tilescreen --key-pass pass:tilescreen --out build/TileScreen.apk $OUT/aligned.apk
apksigner verify build/TileScreen.apk
echo "built build/TileScreen.apk"
