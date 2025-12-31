{
  pkgs ? import <nixpkgs> { },
}:
let
  dtlv = pkgs.stdenv.mkDerivation {
    name = "dtlv";
    src = pkgs.fetchurl {
      url = "https://github.com/juji-io/datalevin/releases/download/0.9.18/dtlv-0.9.18-ubuntu-latest-amd64.zip";
      sha256 = "1jal3yl57k9f9wxvq3h3k0hafndjnx3xr3rfl9jcz4jshn6zjkbs";
    };
    nativeBuildInputs = [
      pkgs.unzip
      pkgs.autoPatchelfHook
    ];
    buildInputs = [
      pkgs.stdenv.cc.cc.lib
      pkgs.zlib
    ];
    unpackPhase = "unzip $src";
    installPhase = ''
      mkdir -p $out/bin
      cp dtlv $out/bin/
      chmod +x $out/bin/dtlv
    '';
  };
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    clojure
    dtlv
    clojure-lsp
  ];

  shellHook = ''
    export LD_LIBRARY_PATH="${pkgs.stdenv.cc.cc.lib}/lib:$LD_LIBRARY_PATH"
  '';
}
