# Build env for Dnsly: JDK 21 + Gradle (base), Android SDK + NDK, Rust + cargo-ndk.
# Everything up to WORKDIR is a verbatim copy of go-data-android's docker/Dockerfile.android,
# so Docker reuses those layers and the SDK is stored once on disk. Keep it identical;
# put Dnsly-only changes below it.
FROM gradle:9.9.1-jdk21

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

ARG CMDLINE_TOOLS=16111833
RUN curl -fsSL -o /tmp/tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip" \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" >/dev/null

WORKDIR /src
# ---- end of layers shared with go-data-android ----

RUN apt-get update \
 && apt-get install -y --no-install-recommends gcc libc6-dev \
 && rm -rf /var/lib/apt/lists/*

ARG NDK=30.0.16248370
ENV ANDROID_NDK_HOME=$ANDROID_HOME/ndk/$NDK
RUN sdkmanager "ndk;$NDK" >/dev/null \
 && rm -rf /root/.android/cache /tmp/*

ENV RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo
ENV PATH=$PATH:/opt/cargo/bin
ARG RUST=1.99.1
ARG CARGO_NDK=4.1.2
RUN curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain $RUST \
      --component clippy,rustfmt --target aarch64-linux-android,x86_64-linux-android \
 && cargo install --locked cargo-ndk --version $CARGO_NDK \
 && rm -rf /opt/cargo/registry /opt/cargo/git

# Last, so editing it does not rebuild the layers above.
LABEL project=dnsly
