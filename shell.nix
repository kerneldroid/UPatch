let
  rust_overlay = import (builtins.fetchTarball "https://github.com/oxalica/rust-overlay/archive/master.tar.gz");
  pkgs = import <nixpkgs> {
    config.android_sdk.accept_license = true;
    config.allowUnfree = true;
    overlays = [ rust_overlay ];
  };
  rustToolchain = pkgs.rust-bin.stable.latest.default.override {
    targets = [ "aarch64-linux-android" ];
  };
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    jdk21
    cmake
    ninja
    git
    rustToolchain
    cargo-ndk
    python3
  ];

  shellHook = ''
    export ANDROID_HOME=/home/nitroarch/Android/Sdk
    export NDK_HOME=$ANDROID_HOME/ndk/29.0.14206865
    export PATH=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
  '';
}
