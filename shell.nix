{
  pkgs ? import <nixpkgs> { },
}:
pkgs.mkShell {
  buildInputs = with pkgs; [
    clojure
    pandoc
    clojure-lsp
  ];
}
