#!/bin/bash
# Build manual do Teste Modelo v1.2 — receita javac+d8+aapt2+zipalign+apksigner.
# Projeto INDEPENDENTE (pacote com.testemodelo.video). Nada do lab anterior ou
# do Lab Arquiteturas é usado — só cópias registradas em ORIGENS.md.
# Keystore PRÓPRIA deste projeto (teste-modelo.keystore; senhas em
# keystore.properties, fora de relatórios).
set -e
export TMPDIR=~/workspace/.tmp-fetch
export JAVA_HOME=/home/hatch/jdk17
export PATH="$JAVA_HOME/bin:$PATH"
VERSION_CODE=4; VERSION_NAME="1.3"; APK_NAME="TesteModelo-v1.3.apk"
SDK=/home/hatch/android-sdk; BT=$SDK/build-tools/34.0.0; ANDROID_JAR=$SDK/platforms/android-34/android.jar
BASE=$(cd "$(dirname "$0")" && pwd)
PROJ=$BASE/app/src/main
OUT=$BASE/build
STORE_FILE=$(grep '^storeFile=' "$BASE/keystore.properties" | cut -d= -f2-)
STORE_PASS=$(grep '^storePassword=' "$BASE/keystore.properties" | cut -d= -f2-)
KEY_ALIAS=$(grep '^keyAlias=' "$BASE/keystore.properties" | cut -d= -f2-)
KEY_PASS=$(grep '^keyPassword=' "$BASE/keystore.properties" | cut -d= -f2-)
KS="$BASE/$STORE_FILE"
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$OUT/gen" "$OUT/res.zip"

echo "== javac =="
find "$OUT/gen" "$PROJ/java" -name "*.java" > "$OUT/sources.txt"
cat "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== d8 =="
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")

echo "== monta APK =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)

echo "== zipalign =="
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== apksigner (v1+v2+v3) =="
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/$APK_NAME" "$OUT/aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME" | head -4
echo "== badging =="
"$BT/aapt2" dump badging "$OUT/$APK_NAME" | head -6
echo "== conteúdo =="
unzip -l "$OUT/$APK_NAME" | grep -E "assets/|classes" | head -12
cp "$OUT/$APK_NAME" "$BASE/$APK_NAME"
ls -la "$BASE/$APK_NAME"
echo "OK: $BASE/$APK_NAME"
