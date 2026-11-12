{
  description = "M5StickC Plus development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config.allowUnfree = true;
            config.android_sdk.accept_license = true;
          };
          python = pkgs.python312.withPackages (ps: [
            ps.pip
            ps.setuptools
            ps.wheel
            ps.pyserial
            ps.requests
          ]);
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "34" "35" ];
            buildToolsVersions = [ "34.0.0" "35.0.0" ];
            includeEmulator = false;
            includeNDK = false;
            includeSources = false;
            includeSystemImages = false;
          };
          androidSdk = androidComposition.androidsdk;
        in
        {
          buildinfra = pkgs.mkShell {
            packages = [ pkgs.bazel_7 pkgs.python312 ];
          };

          default = pkgs.mkShell {
            packages = [
              python
              pkgs.esptool
              pkgs.minicom
              pkgs.picocom
              pkgs.gcc
              pkgs.gnumake
              pkgs.cmake
              pkgs.jdk17
              pkgs.gradle
              pkgs.android-tools
              androidSdk
            ];

            ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
            JAVA_HOME = "${pkgs.jdk17}";

            shellHook = ''
              # adb vendor keys: when the project keeps a secrets/adb directory
              # (next to the bare repo, or at the clone root in a plain clone;
              # git-ignored either way), offer it to adb via ADB_VENDOR_KEYS.
              # adb reads the variable only when its server starts, and from a
              # directory loads only files ending in .adb_key. Silent no-op
              # outside a git repo, without git, without that directory, when
              # the variable is already set (even to empty), or when the path
              # contains ':' (adb splits the variable on colons).
              if [ "''${ADB_VENDOR_KEYS+set}" != set ] && command -v git >/dev/null 2>&1; then
                _qw_common="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
                if [ -n "$_qw_common" ]; then
                  _qw_keys="$(dirname "$_qw_common")/secrets/adb"
                  case "$_qw_keys" in
                    *:*) ;;
                    *) if [ -d "$_qw_keys" ]; then export ADB_VENDOR_KEYS="$_qw_keys"; fi ;;
                  esac
                fi
                unset _qw_common _qw_keys
              fi

              # PlatformIO venv
              if [ ! -d .venv ]; then
                echo "Creating virtualenv for PlatformIO..."
                python3 -m venv .venv --system-site-packages
                .venv/bin/pip install platformio
              fi
              export PATH="$PWD/.venv/bin:$PATH"

              echo "M5StickC Plus dev environment loaded"
              echo "  pio:     $(pio --version 2>/dev/null || echo 'not installed')"
              echo "  esptool: $(esptool version 2>/dev/null || echo 'available')"
              echo "  java:    $(java -version 2>&1 | head -1)"
              echo "  gradle:  $(gradle --version 2>/dev/null | grep Gradle | head -1 || echo 'available')"
            '';
          };
        });
    };
}
