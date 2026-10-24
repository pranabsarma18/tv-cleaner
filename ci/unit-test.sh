#!/bin/sh
# Runs the plain-JVM tests in test/ (no TV or emulator). Needs Java 17 and the Android SDK, like build.sh.
set -e
cd "$(dirname "$0")/.."
[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do [ -d "$j" ] && JAVA_HOME=$j && break; done
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$d" ] && SDK=$d && break; done
JAR=$SDK/platforms/android-34/android.jar
[ -f "$JAR" ] || { echo "Android SDK not found. Set ANDROID_HOME." >&2; exit 1; }
rm -rf out/test; mkdir -p out/test
# The app classes are compiled against android.jar, but only the parts that don't touch Android run here.
javac -nowarn -encoding UTF-8 -cp "$JAR" -d out/test src/com/tvcleaner/home/GitHubContributions.java src/com/tvcleaner/home/Weather.java test/com/tvcleaner/home/*.java
java -cp out/test com.tvcleaner.home.ParseTest
java -cp out/test com.tvcleaner.home.WeatherTest
