{ }:

let
  pkgs = import <nixpkgs> { };
in
  pkgs.stdenv.mkDerivation {
    name = "Woost";
    buildInputs = with pkgs; [
      git
      # scala
      sbt
      docker docker_compose
      #ngrok # github app -> webhooks to localhost
      nodejs-11_x yarn
      # phantomjs
      # gnumake gcc # required for some weird npm things

      # for rust & wasm
      openssl
      wasm-pack
      rustup
    ];

    installPhase= ''
    '';

    shellHook=''
    # export OPENSSL_DIR="${pkgs.openssl.dev}"
    # export OPENSSL_LIB_DIR="${pkgs.openssl.out}/lib"
    # rustup toolchain install nightly
    # rustup default nightly
    # cargo install wasm-pack || true

    echo --- Welcome to woost! ---
    echo "Make sure you have the docker service running and added your user to the group 'docker'."
    echo Now run ./start sbt
    echo In the sbt prompt type: dev
    echo Then point your browser to http://localhost:12345
    '';
  }
