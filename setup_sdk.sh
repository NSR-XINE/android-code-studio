#!/usr/bin/env bash
set -e

SDK_DIR="/data/data/com.termux/files/home/android-sdk"
mkdir -p "$SDK_DIR"
cd "$SDK_DIR"

if [ ! -d "cmdline-tools/latest" ]; then
  echo "Downloading Android Command Line Tools..."
  curl -L https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -o cmdline.zip
  echo "Extracting tools..."
  unzip -q cmdline.zip
  rm cmdline.zip
  mkdir -p cmdline-tools
  mv cmdline-tools cmdline-tools_tmp
  mkdir -p cmdline-tools
  mv cmdline-tools_tmp cmdline-tools/latest
fi

echo "Accepting SDK licenses..."
yes | bash ./cmdline-tools/latest/bin/sdkmanager --licenses --sdk_root="$SDK_DIR"

echo "Installing platforms;android-34 and build-tools;34.0.0..."
bash ./cmdline-tools/latest/bin/sdkmanager --sdk_root="$SDK_DIR" "platforms;android-34" "build-tools;34.0.0"

echo "Configuring local.properties..."
echo "sdk.dir=$SDK_DIR" > /data/data/com.termux/files/home/android-code-studio/local.properties

echo "Android SDK setup complete!"
