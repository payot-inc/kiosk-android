# Android APK 빌드 환경 — 로컬에 JDK/Android SDK 없이 docker 만으로 빌드한다.
# 사용법: ./build.sh (이 디렉토리에서)
#
# AAPT2(안드로이드 리소스 컴파일러)가 x86_64 전용 바이너리라 플랫폼을 고정한다.
# Apple Silicon에서는 Rosetta 에뮬레이션으로 동작 (Docker Desktop 기본 지원).
FROM --platform=linux/amd64 gradle:8.14-jdk17

USER root

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=$ANDROID_HOME
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget unzip \
    && rm -rf /var/lib/apt/lists/*

# Android commandline-tools + SDK 패키지
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/clt.zip \
    && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
    && rm /tmp/clt.zip

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null

WORKDIR /workspace
CMD ["gradle", "assembleDebug", "--no-daemon"]
